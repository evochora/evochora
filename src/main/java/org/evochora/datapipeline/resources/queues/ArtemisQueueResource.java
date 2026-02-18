package org.evochora.datapipeline.resources.queues;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
import org.evochora.datapipeline.api.resources.queues.StreamingBatch;
import org.evochora.datapipeline.resources.AbstractResource;
import org.evochora.datapipeline.utils.JmsUtils;
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
 * providing bounded capacity with backpressure and streaming batch consumption.
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
 * <p>
 * <strong>Streaming consumption:</strong> The consumer session uses {@code SESSION_TRANSACTED}
 * for receive-then-acknowledge semantics. {@link #receiveBatch(int, long, TimeUnit)} receives
 * JMS message references (lightweight, ~100 bytes each) during a token-locked phase, then
 * returns a {@link StreamingBatch} whose iterator lazily deserializes payloads one at a time.
 * This reduces peak heap from {@code N × chunkSize} to {@code 1 × chunkSize}.
 * <p>
 * <strong>Token-Queue Drain Lock:</strong> A second JMS queue ({@code {queueName}.drain-lock})
 * holds exactly one persistent token message. {@link #receiveBatch} acquires the token before
 * receiving messages, ensuring only one consumer receives at a time. The token is released
 * after the receive phase (before iteration/processing), preserving parallelism for the
 * write phase.
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
 *       a pooled session, creates a producer, sends, then returns the session to the pool.</li>
 *   <li>Consumer: Single long-lived session with {@code SESSION_TRANSACTED} — messages
 *       are received but not acknowledged until {@code commit()} is called.</li>
 *   <li>Token: Separate long-lived session with {@code AUTO_ACKNOWLEDGE} (token is consumed
 *       on receive and re-sent on release).</li>
 * </ul>
 *
 * @param <T> Protobuf message type held in the queue
 */
public class ArtemisQueueResource<T extends Message> extends AbstractResource
        implements IContextualResource, IInputQueueResource<T>, IOutputQueueResource<T>,
                   IMemoryEstimatable, AutoCloseable {

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

    // Consumer: dedicated connection + long-lived transacted session
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

            // Consumer connection + long-lived TRANSACTED session
            // Messages are received but not acknowledged until session.commit()
            this.consumerConnection = createTrackedConnection();
            this.consumerSession = consumerConnection.createSession(true, Session.SESSION_TRANSACTED);
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
     * and unpacks the payload.
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

    /**
     * Extracts the Protobuf payload from a JMS BytesMessage.
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

        // Body is already available: reset() was called eagerly after receive()
        // in receiveBatch/receiveAvailable to force large message chunk download.
        // This second reset() is a no-op (already in read mode).
        bytesMsg.reset();
        byte[] data = new byte[(int) bytesMsg.getBodyLength()];
        bytesMsg.readBytes(data);
        return deserialize(data);
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
     * Blocking put. BLOCK policy withholds producer credits when address exceeds maxSizeBytes.
     */
    @Override
    public void put(T element) throws InterruptedException {
        byte[] data = serialize(element);
        try {
            sendMessage(data);
            throughputCounter.recordCount();
        } catch (JMSException e) {
            if (JmsUtils.isInterruptedException(e)) {
                throw new InterruptedException("put() interrupted");
            }
            log.error("Failed to put message to queue '{}'", queueName);
            throw new RuntimeException("Failed to put message to queue: " + queueName, e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Blocking offer with timeout. Uses a dedicated connection with callTimeout.
     */
    @Override
    public boolean offer(T element, long timeout, TimeUnit unit) throws InterruptedException {
        byte[] data = serialize(element);
        try {
            sendMessageWithTimeout(data, unit.toMillis(timeout));
            throughputCounter.recordCount();
            return true;
        } catch (JMSException e) {
            if (JmsUtils.isInterruptedException(e)) {
                throw new InterruptedException("offer() interrupted");
            }
            return false;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void putAll(Collection<T> elements) throws InterruptedException {
        for (T element : elements) {
            put(element);
        }
    }

    /** {@inheritDoc} */
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
     * Sends a serialized message using a temporary connection with callTimeout.
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
     * <strong>Token-based batch receive:</strong> Acquires a token from the drain-lock queue
     * before receiving, ensuring only one consumer receives at a time. This guarantees
     * non-overlapping consecutive batch ranges with competing consumers.
     * <p>
     * <strong>Two-phase design for parallelism:</strong>
     * <ol>
     *   <li><strong>Phase 1 (token-locked, fast):</strong> Receive JMS Message references
     *       (lightweight, ~100 bytes each). No payload deserialization.</li>
     *   <li><strong>Phase 2 (parallel, no token):</strong> Iterator lazily deserializes
     *       each message payload one at a time during processing.</li>
     * </ol>
     * The token is released after Phase 1, so other consumers can receive their batches
     * while this consumer is still processing (writing to storage).
     * <p>
     * <strong>Transacted session:</strong> The consumer session uses {@code SESSION_TRANSACTED}.
     * Messages are in-flight until {@link StreamingBatch#commit()} calls {@code session.commit()}.
     * If processing fails, {@link StreamingBatch#close()} calls {@code session.rollback()},
     * returning messages to the queue for redelivery.
     */
    @Override
    public StreamingBatch<T> receiveBatch(int maxSize, long timeout, TimeUnit unit)
            throws InterruptedException {
        // ATOMIC OPERATION: Entire receive is synchronized to guarantee consecutive ranges.
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
                    return new ArtemisStreamingBatch(Collections.emptyList()); // Timeout
                }
            } catch (JMSException e) {
                if (JmsUtils.isInterruptedException(e)) {
                    throw new InterruptedException("receiveBatch() interrupted acquiring token");
                }
                log.warn("Failed to acquire drain token on queue '{}'", queueName);
                recordError("TOKEN_ACQUIRE_FAILED", "Failed to acquire drain token", e.getMessage());
                return new ArtemisStreamingBatch(Collections.emptyList());
            }

            try {
                // 2. Non-blocking receive of immediately available JMS Messages
                List<jakarta.jms.Message> messages = new ArrayList<>();
                receiveAvailable(messages, maxSize);

                // If we received something OR if the timeout is zero, we're done
                if (!messages.isEmpty() || timeout == 0) {
                    return new ArtemisStreamingBatch(messages);
                }

                // 3. Queue was empty — wait for at least ONE message to arrive
                try {
                    long dataTimeout = Math.max(0, deadlineMs - System.currentTimeMillis());
                    jakarta.jms.Message first = dataConsumer.receive(dataTimeout);
                    if (first == null) {
                        return new ArtemisStreamingBatch(Collections.emptyList()); // Timeout
                    }
                    // Force immediate body download for large messages. Artemis's
                    // ClientConsumerImpl.receive() unconditionally calls discardBody()
                    // on the previous message — reset() populates writableBuffer, making
                    // the subsequent discardBody() a no-op and preserving the body.
                    if (first instanceof BytesMessage bm) {
                        bm.reset();
                    }
                    messages.add(first);
                } catch (JMSException e) {
                    if (JmsUtils.isInterruptedException(e)) {
                        throw new InterruptedException("receiveBatch() interrupted waiting for data");
                    }
                    log.warn("Failed to receive from queue '{}'", queueName);
                    recordError("RECEIVE_FAILED", "Failed to receive message", e.getMessage());
                    return new ArtemisStreamingBatch(Collections.emptyList());
                }

                // 4. Adaptive coalescing: only wait if queue is STILL empty (producer is slow)
                if (coalescingDelayMs > 0 && isDataQueueEmpty()) {
                    // Intentional Thread.sleep — fixed delay, not condition-wait
                    Thread.sleep(coalescingDelayMs);
                }

                // 5. Drain remaining available messages
                receiveAvailable(messages, maxSize - 1);

                return new ArtemisStreamingBatch(messages);

            } finally {
                // 6. Release token (always, even on exception)
                // Token is released HERE — BEFORE iteration/processing
                releaseToken(token);
            }
        }
    }

    /**
     * Non-blocking receive of immediately available JMS messages.
     *
     * @param messages list to add messages to
     * @param max maximum number of messages to receive
     */
    private void receiveAvailable(List<jakarta.jms.Message> messages, int max) {
        try {
            while (messages.size() < max) {
                jakarta.jms.Message msg = dataConsumer.receiveNoWait();
                if (msg == null) {
                    break;
                }
                // Force immediate body download for large messages. Artemis's
                // ClientConsumerImpl.receive() unconditionally calls discardBody()
                // on the previous message — reset() populates writableBuffer, making
                // the subsequent discardBody() a no-op and preserving the body.
                if (msg instanceof BytesMessage bm) {
                    bm.reset();
                }
                messages.add(msg);
            }
        } catch (JMSException e) {
            log.warn("Failed during non-blocking receive on queue '{}' (received {} before error)",
                queueName, messages.size());
            recordError("RECEIVE_FAILED", "Failed during batch receive", e.getMessage());
        }
    }

    /**
     * Checks if the data queue currently has no messages available.
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
                        // Intentional Thread.sleep — linear backoff between JMS retries
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

    // =========================================================================
    // ArtemisStreamingBatch — Inner Class
    // =========================================================================

    /**
     * A {@link StreamingBatch} backed by JMS message references with lazy deserialization.
     * <p>
     * Message references are lightweight (~100 bytes each, pointing to Artemis journal).
     * Actual payload bytes are only read and deserialized when {@link Iterator#next()} is called,
     * keeping at most one deserialized message on the heap at a time.
     * <p>
     * <strong>Commit:</strong> {@code session.commit()} acknowledges all messages in the batch.
     * <strong>Close without commit:</strong> {@code session.rollback()} returns messages to the queue.
     */
    private class ArtemisStreamingBatch implements StreamingBatch<T> {
        private final List<jakarta.jms.Message> messages;
        private boolean committed = false;

        ArtemisStreamingBatch(List<jakarta.jms.Message> messages) {
            this.messages = messages;
        }

        @Override
        public int size() {
            return messages.size();
        }

        @Override
        public Iterator<T> iterator() {
            return new Iterator<T>() {
                private int index = 0;

                @Override
                public boolean hasNext() {
                    return index < messages.size();
                }

                @Override
                public T next() {
                    if (!hasNext()) {
                        throw new java.util.NoSuchElementException();
                    }
                    try {
                        T element = extractPayload(messages.get(index));
                        messages.set(index, null); // Allow GC of serialized message body
                        index++;
                        throughputCounter.recordCount();
                        return element;
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to deserialize message at index " + index
                            + " from queue '" + queueName + "'", e);
                    }
                }
            };
        }

        @Override
        public void commit() {
            if (!committed && !messages.isEmpty()) {
                try {
                    consumerSession.commit();
                    committed = true;
                } catch (JMSException e) {
                    throw new RuntimeException("Failed to commit batch of " + messages.size()
                        + " messages on queue '" + queueName + "'", e);
                }
            }
        }

        @Override
        public void close() {
            if (!committed && !messages.isEmpty()) {
                try {
                    consumerSession.rollback();
                } catch (JMSException e) {
                    // Rollback failure is not recoverable — log and swallow.
                    // Messages will be cleaned up when the session is eventually closed.
                    log.warn("Failed to rollback uncommitted batch of {} messages on queue '{}'",
                        messages.size(), queueName);
                }
            }
        }
    }

    // =========================================================================
    // IContextualResource
    // =========================================================================

    /**
     * {@inheritDoc}
     * <p>
     * Supports the same usage types as {@link InMemoryBlockingQueue}:
     * {@code queue-in}, {@code queue-in-direct}, {@code queue-out}, {@code queue-out-direct}.
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

    /** {@inheritDoc} */
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
     * Closes all JMS resources: producer pool, connections, and connection factory.
     */
    @Override
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

        // Close the underlying factory (releases thread pools, server locators)
        try {
            connectionFactory.close();
        } catch (Exception e) {
            log.debug("Error closing connection factory: {}", e.getMessage());
        }

        log.debug("ArtemisQueueResource '{}' closed", getResourceName());
    }
}
