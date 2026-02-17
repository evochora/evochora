package org.evochora.datapipeline.resources.queues;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSException;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.settings.impl.AddressFullMessagePolicy;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.messaginghub.pooled.jms.JmsPoolConnectionFactory;
import org.evochora.datapipeline.api.memory.IMemoryEstimatable;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.api.resources.IContextualResource;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
import org.evochora.datapipeline.resources.AbstractResource;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.evochora.node.processes.broker.EmbeddedBrokerProcess;
import org.evochora.datapipeline.resources.queues.wrappers.DirectInputQueueWrapper;
import org.evochora.datapipeline.resources.queues.wrappers.DirectOutputQueueWrapper;
import org.evochora.datapipeline.resources.queues.wrappers.MonitoredQueueConsumer;
import org.evochora.datapipeline.resources.queues.wrappers.MonitoredQueueProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Artemis JMS-backed queue that stores messages off-heap (journal/memory-mapped files),
 * providing bounded capacity with backpressure and identical semantics to
 * {@link InMemoryBlockingQueue}.
 * <p>
 * <strong>Motivation:</strong> InMemoryBlockingQueue stores all queued items on the Java heap.
 * For large environments (e.g. 4000x3000 = 12M cells), each TickDataChunk is ~1.1 GB,
 * resulting in ~10 GB heap for a queue of capacity 10. ArtemisQueueResource moves queue
 * contents off-heap into Artemis journal files, reducing heap to ~2-3 GB (only in-flight
 * serialization/deserialization buffers).
 * <p>
 * <strong>Backpressure:</strong> Uses Artemis BLOCK address policy with {@code maxSizeBytes}
 * for native byte-based backpressure. When the address size exceeds the configured byte limit,
 * the broker withholds producer credits and {@code send()} blocks until consumers free space.
 * This works natively for both InVM and TCP modes. For large production messages (&gt;&gt;64 KB),
 * the default producer credit window works perfectly. For small messages, configure
 * {@code producerWindowSize} to ensure per-message credit checks.
 * <p>
 * <strong>Token-Queue Drain Lock:</strong> To guarantee non-overlapping consecutive batch
 * ranges with competing consumers, a second JMS queue ({@code {queueName}.drain-lock})
 * holds exactly one persistent token message. The {@link #drainTo(Collection, int, long, TimeUnit)}
 * method acquires the token before draining, ensuring only one consumer drains at a time.
 * This replicates InMemoryBlockingQueue's {@code synchronized(drainLock)} over the network.
 * <p>
 * <strong>Dual-Mode Deployment:</strong> Works both in-process ({@code vm://0}) with
 * zero-copy InVM transport and distributed ({@code tcp://host:port}) for cloud deployment.
 * <p>
 * <strong>Serialization:</strong> Uses {@code google.protobuf.Any} to wrap messages,
 * embedding the type URL for dynamic deserialization without requiring a parser parameter.
 * <p>
 * <strong>JMS Session Threading:</strong>
 * <ul>
 *   <li>Producer: Session pooling via {@link JmsPoolConnectionFactory} — each send borrows
 *       a pooled session, creates a producer, sends, then returns the session to the pool.
 *       This avoids TCP round-trips for session creation.</li>
 *   <li>Consumer: Single long-lived session with {@code AUTO_ACKNOWLEDGE}</li>
 *   <li>Token: Separate long-lived session (accessed only within token-synchronized drain)</li>
 * </ul>
 *
 * @param <T> Protobuf message type held in the queue
 */
public class ArtemisQueueResource<T extends Message> extends AbstractResource
        implements IContextualResource, IInputQueueResource<T>, IOutputQueueResource<T>, IMemoryEstimatable {

    private static final Logger log = LoggerFactory.getLogger(ArtemisQueueResource.class);

    private final String brokerUrl;
    private final int serverId;
    private final String queueName;
    private final long maxSizeBytes;
    private final int producerWindowSize;
    private final int coalescingDelayMs;
    private final long estimatedBytesPerItem;

    private final ActiveMQConnectionFactory connectionFactory;

    // Producer: pooled session factory — each send borrows a session from the pool
    private final JmsPoolConnectionFactory producerPool;

    // Consumer: dedicated connection + long-lived session
    private final Connection consumerConnection;
    private final Session consumerSession;
    private final MessageConsumer dataConsumer;

    // Token queue for drain lock (distributed mutex across JVMs)
    private final Session tokenSession;
    private final MessageConsumer tokenConsumer;
    private final MessageProducer tokenProducer;

    // Throughput tracking
    private final int metricsWindowSeconds;
    private final SlidingWindowCounter throughputCounter;

    // Local drain lock: serializes JMS session access within the same JVM.
    // JMS sessions are NOT thread-safe, so concurrent consumers in the same JVM
    // must be serialized via this lock. The token queue adds cross-JVM serialization
    // for distributed deployment.
    private final Object drainLock = new Object();

    // Track all connections for shutdown
    private final Set<Connection> openConnections = ConcurrentHashMap.newKeySet();

    /**
     * Creates a new Artemis-backed queue resource.
     *
     * @param name    Resource name (used as default queue name if not specified)
     * @param options Configuration options:
     *                <ul>
     *                  <li>{@code brokerUrl} - Artemis broker URL (default: "vm://0")</li>
     *                  <li>{@code queueName} - JMS queue name (default: resource name)</li>
     *                  <li>{@code maxSizeBytes} - Maximum address size in bytes before BLOCK (default: 1 GB)</li>
     *                  <li>{@code producerWindowSize} - Producer credit window in bytes (default: 0 = Artemis default 64 KB)</li>
     *                  <li>{@code coalescingDelayMs} - Delay for batch coalescing (default: 0)</li>
     *                  <li>{@code metricsWindowSeconds} - Throughput calculation window (default: 5)</li>
     *                  <li>{@code estimatedBytesPerItem} - Override for memory estimation (default: 0 = auto)</li>
     *                </ul>
     */
    public ArtemisQueueResource(String name, Config options) {
        super(name, options);
        Config defaults = ConfigFactory.parseMap(Map.of(
            "maxSizeBytes", 1073741824L, // 1 GB — off-heap journal storage, not Java heap
            "coalescingDelayMs", 0,
            "metricsWindowSeconds", 5
        ));
        Config finalConfig = options.withFallback(defaults);

        this.brokerUrl = finalConfig.hasPath("brokerUrl") ? finalConfig.getString("brokerUrl") : "vm://0";
        this.serverId = EmbeddedBrokerProcess.parseInVmServerId(brokerUrl);
        this.queueName = finalConfig.hasPath("queueName") ? finalConfig.getString("queueName") : name;
        this.maxSizeBytes = finalConfig.getLong("maxSizeBytes");
        this.producerWindowSize = finalConfig.hasPath("producerWindowSize")
            ? finalConfig.getInt("producerWindowSize") : 0;
        this.coalescingDelayMs = finalConfig.getInt("coalescingDelayMs");
        this.metricsWindowSeconds = finalConfig.getInt("metricsWindowSeconds");
        this.estimatedBytesPerItem = finalConfig.hasPath("estimatedBytesPerItem")
            ? finalConfig.getLong("estimatedBytesPerItem") : 0;

        if (maxSizeBytes <= 0) {
            throw new IllegalArgumentException("maxSizeBytes must be positive for resource '" + name + "'.");
        }
        if (coalescingDelayMs < 0) {
            throw new IllegalArgumentException("coalescingDelayMs cannot be negative for resource '" + name + "'.");
        }
        if (estimatedBytesPerItem < 0) {
            throw new IllegalArgumentException("estimatedBytesPerItem cannot be negative for resource '" + name + "'.");
        }

        this.throughputCounter = new SlidingWindowCounter(metricsWindowSeconds);

        // Configure queue-specific address settings (BLOCK policy with byte-based limit)
        configureQueueAddressSettings();

        try {
            this.connectionFactory = new ActiveMQConnectionFactory(brokerUrl);
            if (producerWindowSize > 0) {
                this.connectionFactory.setProducerWindowSize(producerWindowSize);
            }

            // Producer pool: wraps the raw factory to pool sessions for send operations
            this.producerPool = new JmsPoolConnectionFactory();
            this.producerPool.setConnectionFactory(connectionFactory);
            this.producerPool.setMaxConnections(1);
            this.producerPool.start();

            // Consumer connection + long-lived session (AUTO_ACKNOWLEDGE: messages acked on receive)
            this.consumerConnection = createTrackedConnection();
            this.consumerSession = consumerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue dataQueue = consumerSession.createQueue(queueName);
            this.dataConsumer = consumerSession.createConsumer(dataQueue);

            // Token queue connection (separate session for isolation, AUTO_ACKNOWLEDGE)
            String tokenQueueName = queueName + ".drain-lock";
            this.tokenSession = consumerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue tokenQueue = tokenSession.createQueue(tokenQueueName);
            this.tokenConsumer = tokenSession.createConsumer(tokenQueue);
            this.tokenProducer = tokenSession.createProducer(tokenQueue);
            this.tokenProducer.setDeliveryMode(DeliveryMode.PERSISTENT);

            // Seed the token queue with exactly one token if empty
            seedTokenIfEmpty(tokenQueueName);

            log.debug("ArtemisQueueResource '{}' initialized (url={}, queue={}, maxSizeBytes={}, coalescing={}ms)",
                name, brokerUrl, queueName, maxSizeBytes, coalescingDelayMs);

        } catch (JMSException e) {
            // Clean up any connections created before the failure.
            // close() iterates openConnections and cascades to sessions/consumers/producers.
            try {
                close();
            } catch (Exception closeEx) {
                log.debug("Error during cleanup after failed initialization: {}", closeEx.getMessage());
            }
            log.error("Failed to initialize ArtemisQueueResource '{}'", name);
            throw new RuntimeException("Failed to initialize ArtemisQueueResource: " + name, e);
        }
    }

    /**
     * Configures BLOCK-based address settings for the data queue.
     * <p>
     * Unlike topics (which use PAGE policy to never block producers), queues use BLOCK
     * policy to provide backpressure. The queue depth is limited by byte size
     * ({@code maxSizeBytes}), enforced natively via Artemis producer credits.
     * <p>
     * The token queue inherits global "#" defaults (PAGE policy). It only ever holds 0-1
     * messages, so no special address settings are needed.
     */
    private void configureQueueAddressSettings() {
        ActiveMQServer server = EmbeddedBrokerProcess.getServer(serverId);
        if (server == null) {
            log.debug("No embedded server available for address settings — using broker defaults");
            return;
        }

        // Data queue: BLOCK policy with byte-based limit
        AddressSettings dataSettings = new AddressSettings();
        dataSettings.setAddressFullMessagePolicy(AddressFullMessagePolicy.BLOCK);
        dataSettings.setMaxSizeBytes(maxSizeBytes);
        dataSettings.setMaxSizeMessages(-1); // Disable message-count limit, use byte size only
        dataSettings.setDefaultMaxConsumers(-1); // Unlimited consumers
        server.getAddressSettingsRepository().addMatch(queueName, dataSettings);

        // Token queue: inherits global "#" settings (PAGE policy).
        // The token queue only ever holds 0-1 messages, so no special settings needed.

        log.debug("Address settings configured: data queue '{}' (BLOCK, maxSizeBytes={})",
            queueName, maxSizeBytes);
    }

    /**
     * Seeds the token queue with exactly one persistent token message if it is currently empty.
     * <p>
     * Uses a non-blocking receive to check if a token exists, then either returns it
     * or creates a new one. The token is a persistent empty BytesMessage that survives
     * broker restarts.
     *
     * @param tokenQueueName the token queue name (for logging)
     * @throws JMSException if JMS operations fail
     */
    private void seedTokenIfEmpty(String tokenQueueName) throws JMSException {
        // Try to receive existing token (non-blocking)
        // With AUTO_ACKNOWLEDGE, receiving the token also acknowledges it.
        jakarta.jms.Message existingToken = tokenConsumer.receiveNoWait();

        if (existingToken != null) {
            // Token exists (already acknowledged by AUTO_ACKNOWLEDGE) — re-send to put it back
            BytesMessage newToken = tokenSession.createBytesMessage();
            tokenProducer.send(newToken);
            log.debug("Token queue '{}' already seeded (re-sent existing token)", tokenQueueName);
        } else {
            // No token found — seed with a new one
            BytesMessage token = tokenSession.createBytesMessage();
            tokenProducer.send(token);
            log.debug("Token queue '{}' seeded with initial token", tokenQueueName);
        }
    }

    // =========================================================================
    // Connection Management
    // =========================================================================

    /**
     * Creates a tracked JMS connection.
     * <p>
     * All created connections are tracked for cleanup during {@link #close()}.
     * Connections are started immediately to allow consuming.
     *
     * @return a started JMS connection
     * @throws JMSException if connection creation fails
     */
    private Connection createTrackedConnection() throws JMSException {
        Connection connection = connectionFactory.createConnection();
        connection.start();
        openConnections.add(connection);
        return connection;
    }

    // =========================================================================
    // Serialization
    // =========================================================================

    /**
     * Serializes a Protobuf message to bytes using {@code google.protobuf.Any}.
     * <p>
     * The type URL embedded in Any enables dynamic deserialization without requiring
     * the caller to pass a parser or class reference.
     *
     * @param element the Protobuf message to serialize
     * @return serialized bytes
     */
    private byte[] serialize(T element) {
        return Any.pack(element).toByteArray();
    }

    /**
     * Deserializes bytes back to a Protobuf message using {@code google.protobuf.Any}.
     * <p>
     * Extracts the type URL from the Any wrapper, loads the corresponding class,
     * and unpacks the payload. This is the same pattern used by
     * {@code AbstractTopicDelegateReader}.
     *
     * @param data the serialized bytes
     * @return the deserialized Protobuf message
     * @throws InvalidProtocolBufferException if the data is not valid protobuf
     */
    private T deserialize(byte[] data) throws InvalidProtocolBufferException {
        Any any = Any.parseFrom(data);

        // Extract fully qualified class name from type URL
        // Format: "type.googleapis.com/org.evochora.datapipeline.api.contracts.TickDataChunk"
        String typeUrl = any.getTypeUrl();
        String className = typeUrl.substring(typeUrl.indexOf('/') + 1);

        try {
            Class<?> rawClass = Class.forName(className);
            if (!Message.class.isAssignableFrom(rawClass)) {
                throw new InvalidProtocolBufferException(
                    "Class '" + className + "' from type URL '" + typeUrl +
                    "' does not implement com.google.protobuf.Message");
            }
            @SuppressWarnings("unchecked")
            Class<T> messageClass = (Class<T>) rawClass;
            return any.unpack(messageClass);
        } catch (ClassNotFoundException e) {
            throw new InvalidProtocolBufferException(
                "Cannot find class for type URL: " + typeUrl + ". " +
                "Ensure the Protobuf class is on the classpath.");
        }
    }

    // =========================================================================
    // IOutputQueueResource — Producer Methods
    // =========================================================================

    /**
     * {@inheritDoc}
     * <p>
     * Non-blocking offer. Checks the address size via Artemis server API and returns
     * false immediately if the byte limit is reached.
     */
    @Override
    public boolean offer(T element) {
        if (isQueueAtCapacity()) {
            return false;
        }
        byte[] data = serialize(element);
        try {
            sendMessage(data);
            throughputCounter.recordCount();
            return true;
        } catch (JMSException e) {
            log.warn("Failed to offer message to queue '{}'", queueName);
            recordError("OFFER_FAILED", "Failed to send message", e.getMessage());
            return false;
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Blocking put. Sends the message via the producer pool. If the address has exceeded
     * {@code maxSizeBytes}, the Artemis BLOCK policy withholds producer credits and the
     * {@code send()} call blocks until consumers free space.
     */
    @Override
    public void put(T element) throws InterruptedException {
        byte[] data = serialize(element);
        try {
            sendMessage(data);
            throughputCounter.recordCount();
        } catch (JMSException e) {
            if (isInterruptedException(e)) {
                throw new InterruptedException("put() interrupted");
            }
            log.error("Failed to put message to queue '{}'", queueName);
            throw new RuntimeException("Failed to put message to queue: " + queueName, e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Blocking offer with timeout. Attempts to send the message with a bounded wait.
     * If the queue is at capacity (BLOCK policy), the send blocks until space is available
     * or the timeout expires. Uses a dedicated connection with {@code callTimeout} to
     * enforce the time limit.
     */
    @Override
    public boolean offer(T element, long timeout, TimeUnit unit) throws InterruptedException {
        byte[] data = serialize(element);
        try {
            sendMessageWithTimeout(data, unit.toMillis(timeout));
            throughputCounter.recordCount();
            return true;
        } catch (JMSException e) {
            if (isInterruptedException(e)) {
                throw new InterruptedException("offer() interrupted");
            }
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void putAll(Collection<T> elements) throws InterruptedException {
        for (T element : elements) {
            put(element);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int offerAll(Collection<T> elements) {
        if (elements == null) {
            throw new NullPointerException("elements collection cannot be null");
        }
        int count = 0;
        for (T element : elements) {
            if (element == null) {
                throw new NullPointerException("collection cannot contain null elements");
            }
            if (offer(element)) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    /**
     * Sends a serialized message to the data queue using a pooled session.
     * <p>
     * Borrows a session from the {@link #producerPool}, creates a producer, sends,
     * then closes (returns session to pool). The session pool avoids TCP round-trips
     * for session creation while ensuring each send gets fresh producer credits from
     * the broker — preventing credit pre-fetch from bypassing the BLOCK policy.
     * <p>
     * Thread safety: queue producers are single-threaded (one service loop per resource
     * wrapper), so no synchronization is needed.
     *
     * @param data the serialized message bytes
     * @throws JMSException if sending fails
     */
    private void sendMessage(byte[] data) throws JMSException {
        try (Connection conn = producerPool.createConnection()) {
            conn.start();
            try (Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                Queue queue = session.createQueue(queueName);
                try (MessageProducer producer = session.createProducer(queue)) {
                    producer.setDeliveryMode(DeliveryMode.PERSISTENT);
                    BytesMessage message = session.createBytesMessage();
                    message.writeBytes(data);
                    producer.send(message);
                }
            }
        }
    }

    /**
     * Sends a serialized message using a temporary connection with {@code callTimeout}.
     * <p>
     * Creates a new {@link ActiveMQConnectionFactory} with the specified timeout so the
     * send will fail with a JMSException if the queue remains at capacity beyond the timeout.
     * Connection-per-call is acceptable because {@code offer(timeout)} is not the hot path.
     * <p>
     * Used by {@link #offer(Message, long, TimeUnit)} which needs a bounded wait.
     *
     * @param data the serialized message bytes
     * @param timeoutMs send timeout in milliseconds
     * @throws JMSException if sending fails or times out
     */
    private void sendMessageWithTimeout(byte[] data, long timeoutMs) throws JMSException {
        try (ActiveMQConnectionFactory timeoutFactory = new ActiveMQConnectionFactory(brokerUrl)) {
            timeoutFactory.setBlockOnDurableSend(true);
            timeoutFactory.setCallTimeout(timeoutMs);
            if (producerWindowSize > 0) {
                timeoutFactory.setProducerWindowSize(producerWindowSize);
            }

            try (Connection connection = timeoutFactory.createConnection()) {
                connection.start();
                try (Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                    Queue queue = session.createQueue(queueName);
                    try (MessageProducer producer = session.createProducer(queue)) {
                        producer.setDeliveryMode(DeliveryMode.PERSISTENT);
                        BytesMessage message = session.createBytesMessage();
                        message.writeBytes(data);
                        producer.send(message);
                    }
                }
            }
        }
    }

    /**
     * Checks if the data queue address has reached its byte-size limit.
     * <p>
     * Uses the Artemis {@code PagingStore.getAddressSize()} API — the same metric that
     * the BLOCK policy checks internally. Falls back to assuming not at capacity if the
     * server is unavailable (allows send to proceed; BLOCK policy provides the real backpressure).
     *
     * @return true if the address size has reached or exceeded {@code maxSizeBytes}
     */
    private boolean isQueueAtCapacity() {
        ActiveMQServer server = EmbeddedBrokerProcess.getServer(serverId);
        if (server == null) {
            return false; // Can't check, let send proceed (BLOCK policy handles it)
        }
        try {
            var pgStore = server.getPagingManager().getPageStore(SimpleString.of(queueName));
            if (pgStore != null) {
                return pgStore.getAddressSize() >= maxSizeBytes;
            }
            return false;
        } catch (Exception e) {
            return false; // Can't check, let send proceed
        }
    }

    // =========================================================================
    // IInputQueueResource — Consumer Methods
    // =========================================================================

    /**
     * {@inheritDoc}
     * <p>
     * Non-blocking poll. Returns immediately with the next message or empty.
     */
    @Override
    public Optional<T> poll() {
        try {
            jakarta.jms.Message msg = dataConsumer.receiveNoWait();
            if (msg == null) {
                return Optional.empty();
            }
            T element = extractPayload(msg);
            throughputCounter.recordCount();
            return Optional.of(element);
        } catch (Exception e) {
            log.warn("Failed to poll from queue '{}'", queueName);
            recordError("POLL_FAILED", "Failed to poll message", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Blocking take. Waits indefinitely until a message is available.
     */
    @Override
    public T take() throws InterruptedException {
        try {
            jakarta.jms.Message msg = dataConsumer.receive(0);
            if (msg == null) {
                throw new InterruptedException("take() returned null — consumer likely closed");
            }
            T element = extractPayload(msg);
            throughputCounter.recordCount();
            return element;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof JMSException jmsEx && isInterruptedException(jmsEx)) {
                throw new InterruptedException("take() interrupted");
            }
            log.error("Failed to take from queue '{}'", queueName);
            throw new RuntimeException("Failed to take from queue: " + queueName, e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Blocking poll with timeout.
     */
    @Override
    public Optional<T> poll(long timeout, TimeUnit unit) throws InterruptedException {
        try {
            long timeoutMs = unit.toMillis(timeout);
            jakarta.jms.Message msg = dataConsumer.receive(timeoutMs);
            if (msg == null) {
                return Optional.empty();
            }
            T element = extractPayload(msg);
            throughputCounter.recordCount();
            return Optional.of(element);
        } catch (Exception e) {
            if (e instanceof JMSException jmsEx && isInterruptedException(jmsEx)) {
                throw new InterruptedException("poll() interrupted");
            }
            log.warn("Failed to poll from queue '{}'", queueName);
            recordError("POLL_FAILED", "Failed to receive message", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Non-blocking drain. Receives up to {@code maxElements} immediately available messages.
     */
    @Override
    public int drainTo(Collection<? super T> collection, int maxElements) {
        int count = 0;
        try {
            while (count < maxElements) {
                jakarta.jms.Message msg = dataConsumer.receiveNoWait();
                if (msg == null) {
                    break;
                }
                T element = extractPayload(msg);
                collection.add(element);
                throughputCounter.recordCount();
                count++;
            }
        } catch (Exception e) {
            log.warn("Failed during drainTo on queue '{}' (drained {} before error)", queueName, count);
            recordError("DRAIN_FAILED", "Failed during drain", e.getMessage());
        }
        return count;
    }

    /**
     * {@inheritDoc}
     * <p>
     * <strong>Token-based drain lock:</strong> Acquires a token from the drain-lock queue
     * before draining, ensuring only one consumer drains at a time. This guarantees
     * non-overlapping consecutive batch ranges with competing consumers.
     * <p>
     * <strong>Flow:</strong>
     * <ol>
     *   <li>Acquire drain token (blocks until available or timeout)</li>
     *   <li>Non-blocking drain of immediately available messages</li>
     *   <li>If queue was empty: wait for first message with remaining timeout</li>
     *   <li>Adaptive coalescing: if queue is still empty after first message, wait briefly</li>
     *   <li>Drain remaining available messages</li>
     *   <li>Release token (always, even on exception)</li>
     * </ol>
     * <p>
     * This replicates the exact semantics of InMemoryBlockingQueue's
     * {@code synchronized(drainLock)} block over the network.
     */
    @Override
    public int drainTo(Collection<? super T> collection, int maxElements, long timeout, TimeUnit unit)
            throws InterruptedException {
        // ATOMIC OPERATION: Entire drain is synchronized to guarantee consecutive ranges.
        // drainLock serializes JMS session access within the same JVM (sessions are NOT thread-safe).
        // Token queue adds cross-JVM serialization for distributed deployment.
        synchronized (drainLock) {
            long deadlineMs = System.currentTimeMillis() + unit.toMillis(timeout);

            // 1. Acquire drain token (blocks until available or timeout)
            jakarta.jms.Message token;
            try {
                long tokenTimeout = Math.max(0, deadlineMs - System.currentTimeMillis());
                token = tokenConsumer.receive(tokenTimeout);
                if (token == null) {
                    return 0; // Timeout waiting for token
                }
            } catch (JMSException e) {
                if (isInterruptedException(e)) {
                    throw new InterruptedException("drainTo() interrupted acquiring token");
                }
                log.warn("Failed to acquire drain token on queue '{}'", queueName);
                recordError("TOKEN_ACQUIRE_FAILED", "Failed to acquire drain token", e.getMessage());
                return 0;
            }

            try {
                // 2. Non-blocking drain of immediately available messages
                int drained = drainTo(collection, maxElements);

                // If we drained something OR if the timeout is zero, we're done
                if (drained > 0 || timeout == 0) {
                    return drained;
                }

                // 3. Queue was empty — wait for at least ONE element to arrive
                long dataTimeout = Math.max(0, deadlineMs - System.currentTimeMillis());
                Optional<T> first = poll(dataTimeout, TimeUnit.MILLISECONDS);
                if (first.isEmpty()) {
                    return 0; // Timeout
                }
                collection.add(first.get());

                // 4. Adaptive coalescing: only wait if queue is STILL empty (producer is slow)
                if (coalescingDelayMs > 0 && isDataQueueEmpty()) {
                    Thread.sleep(coalescingDelayMs);
                }

                // 5. Drain remaining available messages
                int additional = drainTo(collection, maxElements - 1);
                return 1 + additional;

            } finally {
                // 6. Release token (always, even on exception)
                releaseToken(token);
            }
        }
    }

    /**
     * Checks if the data queue currently has no messages available.
     * <p>
     * Uses Artemis server API to check the queue message count without consuming.
     * Falls back to assuming non-empty if the server is unavailable.
     *
     * @return true if no messages are immediately available
     */
    private boolean isDataQueueEmpty() {
        ActiveMQServer server = EmbeddedBrokerProcess.getServer(serverId);
        if (server == null) {
            return false; // Can't check, assume non-empty (skip coalescing)
        }
        try {
            var queueControl = server.locateQueue(SimpleString.of(queueName));
            if (queueControl != null) {
                return queueControl.getMessageCount() == 0;
            }
            return false;
        } catch (Exception e) {
            return false; // Can't check, assume non-empty
        }
    }

    /**
     * Releases the drain token back to the token queue.
     * <p>
     * Acknowledges the consumed token and sends a new one. This is done in a
     * finally block to ensure the token is always released, even on exception.
     *
     * @param token the consumed token message
     */
    private void releaseToken(jakarta.jms.Message token) {
        JMSException lastException = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                // Token was already acknowledged on receive (AUTO_ACKNOWLEDGE).
                // Send a new token to make it available for the next consumer.
                BytesMessage newToken = tokenSession.createBytesMessage();
                tokenProducer.send(newToken);
                return;
            } catch (JMSException e) {
                lastException = e;
                log.warn("Failed to release drain token on queue '{}' (attempt {}/3)", queueName, attempt);
                if (attempt < 3) {
                    try {
                        Thread.sleep(50L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        recordError("TOKEN_RELEASE_FAILED", "Failed to release drain token after 3 attempts",
            lastException != null ? lastException.getMessage() : "unknown");
        log.error("Failed to release drain token on queue '{}' after 3 attempts — drain lock is now STUCK", queueName);
        throw new RuntimeException("Failed to release drain token — lock stuck on queue: " + queueName, lastException);
    }

    /**
     * Extracts the Protobuf payload from a JMS BytesMessage.
     * <p>
     * Message acknowledgment is handled automatically by the session (AUTO_ACKNOWLEDGE mode).
     *
     * @param msg the JMS message
     * @return the deserialized Protobuf message
     * @throws JMSException if JMS operations fail
     * @throws InvalidProtocolBufferException if deserialization fails
     */
    private T extractPayload(jakarta.jms.Message msg) throws JMSException, InvalidProtocolBufferException {
        if (!(msg instanceof BytesMessage bytesMsg)) {
            throw new JMSException("Expected BytesMessage, got: " + msg.getClass().getName());
        }

        byte[] data = new byte[(int) bytesMsg.getBodyLength()];
        bytesMsg.readBytes(data);

        return deserialize(data);
    }

    // =========================================================================
    // IContextualResource
    // =========================================================================

    /**
     * {@inheritDoc}
     * <p>
     * Supports the same usage types as {@link InMemoryBlockingQueue}:
     * {@code queue-in}, {@code queue-in-direct}, {@code queue-out}, {@code queue-out-direct}.
     * <p>
     * <b>Remote broker limitation:</b> When connected to a remote broker (non-InVM),
     * the embedded server API is unavailable for queue depth inspection. Both
     * {@code queue-in} and {@code queue-out} will always report {@link UsageState#ACTIVE},
     * regardless of actual queue state. This does not affect correctness (Artemis BLOCK
     * policy and JMS blocking provide real backpressure), but monitoring dashboards
     * will not reflect queue fullness or emptiness in distributed mode.
     */
    @Override
    public UsageState getUsageState(String usageType) {
        if (usageType == null) {
            throw new IllegalArgumentException(String.format(
                "Queue resource '%s' requires a non-null usageType", getResourceName()
            ));
        }

        return switch (usageType) {
            case "queue-in", "queue-in-direct" ->
                isDataQueueEmpty() ? UsageState.WAITING : UsageState.ACTIVE;
            case "queue-out", "queue-out-direct" ->
                isQueueAtCapacity() ? UsageState.WAITING : UsageState.ACTIVE;
            default -> throw new IllegalArgumentException(String.format(
                "Unknown usageType '%s' for queue resource '%s'", usageType, getResourceName()
            ));
        };
    }

    /**
     * {@inheritDoc}
     * <p>
     * Returns monitored or direct wrappers identical to {@link InMemoryBlockingQueue}.
     */
    @Override
    public IWrappedResource getWrappedResource(ResourceContext context) {
        if (context.usageType() == null) {
            throw new IllegalArgumentException(String.format(
                "Queue resource '%s' requires a usageType in the binding URI. " +
                "Expected format: 'usageType:%s' where usageType is one of: " +
                "queue-in, queue-in-direct, queue-out, queue-out-direct",
                getResourceName(), getResourceName()
            ));
        }

        return switch (context.usageType()) {
            case "queue-in" -> new MonitoredQueueConsumer<>(this, context);
            case "queue-in-direct" -> new DirectInputQueueWrapper<>(this);
            case "queue-out" -> new MonitoredQueueProducer<>(this, context);
            case "queue-out-direct" -> new DirectOutputQueueWrapper<>(this);
            default -> throw new IllegalArgumentException(String.format(
                "Unsupported usage type '%s' for queue resource '%s'. " +
                "Supported types: queue-in, queue-in-direct, queue-out, queue-out-direct",
                context.usageType(), getResourceName()
            ));
        };
    }

    // =========================================================================
    // IMemoryEstimatable
    // =========================================================================

    /**
     * {@inheritDoc}
     * <p>
     * Estimates minimal heap usage since queue contents are stored off-heap in Artemis.
     * Only in-flight serialization/deserialization buffers contribute to heap.
     */
    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // Only in-flight buffers: 2x item size (one produce + one consume in flight)
        // Plus small overhead for JMS session objects
        long bytesPerItem = estimatedBytesPerItem > 0
            ? estimatedBytesPerItem
            : params.estimateBytesPerChunk();
        long inFlightBytes = 2 * bytesPerItem;
        long sessionOverhead = 64L * 1024; // ~64KB for JMS sessions

        long totalBytes = inFlightBytes + sessionOverhead;

        String explanation = String.format("2 × %s/item (in-flight) + 64 KB sessions (queue contents off-heap in Artemis)",
            SimulationParameters.formatBytes(bytesPerItem));

        return List.of(new MemoryEstimate(
            getResourceName(),
            totalBytes,
            explanation,
            MemoryEstimate.Category.QUEUE
        ));
    }

    // =========================================================================
    // Metrics & Monitoring
    // =========================================================================

    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);
        metrics.put("max_size_bytes", maxSizeBytes);
        metrics.put("address_size_bytes", getAddressSize());
        metrics.put("message_count", getQueueMessageCount());
        metrics.put("throughput_per_sec", throughputCounter.getRate());
    }

    /**
     * Returns the current message count in the data queue via server API.
     * <p>
     * Returns 0 if the server is unavailable (e.g. remote broker deployment).
     *
     * @return current message count
     */
    private long getQueueMessageCount() {
        ActiveMQServer server = EmbeddedBrokerProcess.getServer(serverId);
        if (server == null) {
            return 0;
        }
        try {
            var queueControl = server.locateQueue(SimpleString.of(queueName));
            if (queueControl != null) {
                return queueControl.getMessageCount();
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Clears errors from the resource's error list based on a predicate.
     * <p>
     * Used by wrapper resources to clear context-specific errors.
     *
     * @param filter A predicate to select which errors to remove.
     */
    public void clearErrors(Predicate<OperationalError> filter) {
        clearErrorsIf(filter);
    }

    /**
     * Returns the configured maximum address size in bytes.
     *
     * @return The byte limit before BLOCK policy halts producers.
     */
    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }

    /**
     * Returns the current address size in bytes via the Artemis PagingStore API.
     * <p>
     * Returns 0 if the server is unavailable (e.g. remote broker deployment).
     *
     * @return current address size in bytes
     */
    private long getAddressSize() {
        ActiveMQServer server = EmbeddedBrokerProcess.getServer(serverId);
        if (server == null) {
            return 0;
        }
        try {
            var pgStore = server.getPagingManager().getPageStore(SimpleString.of(queueName));
            if (pgStore != null) {
                return pgStore.getAddressSize();
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Closes all JMS connections and sessions.
     * <p>
     * Closes consumer sessions first (stops message delivery), then connections.
     * Token and data sessions are closed via their parent connections.
     */
    public void close() throws Exception {
        // Stop the producer session pool first
        try {
            producerPool.stop();
        } catch (Exception e) {
            log.debug("Error stopping producer pool: {}", e.getMessage());
        }

        // Close all tracked connections — this cascades to sessions, consumers, producers
        for (Connection conn : openConnections) {
            try {
                conn.close();
            } catch (Exception e) {
                // Ignore connection close errors during shutdown
            }
        }
        openConnections.clear();
        log.debug("ArtemisQueueResource '{}' closed", getResourceName());
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    /**
     * Checks if a JMSException wraps an InterruptedException.
     * <p>
     * Same pattern as {@code ArtemisTopicWriterDelegate.isInterruptedException()}.
     *
     * @param e the JMS exception to check
     * @return true if the root cause is InterruptedException
     */
    private static boolean isInterruptedException(JMSException e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof InterruptedException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
