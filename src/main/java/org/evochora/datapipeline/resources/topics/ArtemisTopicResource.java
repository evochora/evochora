package org.evochora.datapipeline.resources.topics;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;

import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.evochora.datapipeline.api.memory.IMemoryEstimatable;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.topics.ITopicReader;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;
import org.evochora.node.processes.broker.EmbeddedBrokerProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.Message;
import com.typesafe.config.Config;

/**
 * ActiveMQ Artemis-based topic implementation.
 * <p>
 * Provides high-performance, persistent messaging using either:
 * <ul>
 *   <li>In-VM transport (vm://0) - Ultra low latency, in-process</li>
 *   <li>TCP transport (tcp://host:port) - Networked, distributed</li>
 * </ul>
 * <p>
 * <strong>Persistence:</strong>
 * Uses Artemis Journal (Append-Only) for durability. Faster than SQL-based H2.
 * <p>
 * <strong>Competing Consumers:</strong>
 * Supported natively via JMS 2.0 Shared Subscriptions.
 * <p>
 * <strong>Stuck Message Handling:</strong>
 * A watchdog thread monitors all reader delegates. If a message is held without
 * acknowledgment longer than {@code claimTimeout}, the session is recovered,
 * causing the broker to redeliver the message to another consumer.
 */
public class ArtemisTopicResource<T extends Message> extends AbstractTopicResource<T, javax.jms.Message> 
        implements IMemoryEstimatable {
    
    private static final Logger log = LoggerFactory.getLogger(ArtemisTopicResource.class);
    
    /** Fast-path registry of known subscriptions (format: "topicName::subscriptionName"). */
    private static final Set<String> knownSubscriptions = ConcurrentHashMap.newKeySet();
    
    private final String brokerUrl;
    private final String baseTopicName; // Renamed from topicName
    private final ConnectionFactory connectionFactory;
    private final int claimTimeoutSeconds;
    
    // The effective topic name, incorporating the runId if set
    private volatile String effectiveTopicName;
    
    // Track connections to close them on shutdown
    private final Set<Connection> openConnections = ConcurrentHashMap.newKeySet();
    
    // Track reader delegates for stuck message detection
    private final Set<ArtemisTopicReaderDelegate<T>> readerDelegates = ConcurrentHashMap.newKeySet();
    
    private final long maxSizeBytesForEstimation;

    // Watchdog for stuck message detection (null if claimTimeout disabled)
    private final ScheduledExecutorService watchdog;

    /**
     * Creates a new Artemis topic resource.
     *
     * @param name    Resource name (used as default topic name if not specified)
     * @param options Configuration options including:
     *                <ul>
     *                  <li>{@code brokerUrl} - Artemis broker URL (default: "vm://0")</li>
     *                  <li>{@code topicName} - JMS topic name (default: resource name)</li>
     *                  <li>{@code claimTimeout} - Seconds before stuck message recovery (default: 300, 0=disabled)</li>
     *                </ul>
     */
    public ArtemisTopicResource(String name, Config options) {
        super(name, options);
        
        this.brokerUrl = options.hasPath("brokerUrl") ? options.getString("brokerUrl") : "vm://0";
        this.baseTopicName = options.hasPath("topicName") ? options.getString("topicName") : name;
        this.effectiveTopicName = this.baseTopicName; // Default to base name until runId is set
        this.claimTimeoutSeconds = options.hasPath("claimTimeout") ? options.getInt("claimTimeout") : 300;
        this.maxSizeBytesForEstimation = options.hasPath("maxSizeBytesForEstimation")
            ? options.getLong("maxSizeBytesForEstimation")
            : 20L * 1024 * 1024; // Default: 20MB. MUST match default in EmbeddedBrokerProcess!
        
        // Create ConnectionFactory
        try {
            this.connectionFactory = new ActiveMQConnectionFactory(brokerUrl);
            
            // Test connection
            try (Connection conn = connectionFactory.createConnection()) {
                // Just test if we can connect
            }
            
            log.debug("Artemis topic resource '{}' initialized (url={}, topic={}, claimTimeout={}s)", 
                name, brokerUrl, effectiveTopicName, claimTimeoutSeconds);
            
        } catch (JMSException e) {
            log.error("Failed to initialize Artemis topic resource '{}'", name);
            recordError("INIT_FAILED", "Artemis initialization failed", "Topic: " + name + ", Url: " + brokerUrl);
            throw new RuntimeException("Failed to initialize Artemis topic: " + name, e);
        }
        
        // Start watchdog if claimTimeout is enabled
        if (claimTimeoutSeconds > 0) {
            this.watchdog = Executors.newSingleThreadScheduledExecutor(
                r -> {
                    Thread t = new Thread(r, "artemis-watchdog-" + name);
                    t.setDaemon(true);
                    return t;
                });
            
            // Check interval: claimTimeout/10 but at least 10 seconds, at most 60 seconds
            long checkIntervalSeconds = Math.max(10, Math.min(60, claimTimeoutSeconds / 10));
            watchdog.scheduleAtFixedRate(
                this::checkStuckMessages,
                checkIntervalSeconds,
                checkIntervalSeconds,
                TimeUnit.SECONDS);
            
            log.debug("Artemis watchdog started for '{}' (check interval: {}s)", name, checkIntervalSeconds);
        } else {
            this.watchdog = null;
            log.debug("Artemis watchdog disabled for '{}' (claimTimeout=0)", name);
        }
    }
    
    /**
     * Checks all reader delegates for stuck messages.
     * <p>
     * Called periodically by the watchdog thread. If a delegate has held a message
     * longer than {@code claimTimeoutSeconds}, forces session recovery to trigger
     * broker-side redelivery.
     */
    private void checkStuckMessages() {
        if (claimTimeoutSeconds <= 0) {
            return;
        }
        
        Instant threshold = Instant.now().minusSeconds(claimTimeoutSeconds);
        
        for (ArtemisTopicReaderDelegate<T> reader : readerDelegates) {
            if (reader.isStuck(threshold)) {
                String consumerGroup = reader.getSubscriptionName();
                log.warn("Stuck message detected in topic '{}' (consumer: {}), forcing session recovery for redelivery",
                    effectiveTopicName, consumerGroup);
                
                try {
                    reader.recover();
                    recordError("STUCK_MESSAGE_RECOVERED", 
                        "Stuck message recovered via session.recover()", 
                        "Consumer: " + consumerGroup + ", Timeout: " + claimTimeoutSeconds + "s");
                } catch (Exception e) {
                    log.error("Failed to recover session for stuck message in topic '{}' (consumer: {})",
                        effectiveTopicName, consumerGroup);
                    recordError("STUCK_MESSAGE_RECOVERY_FAILED",
                        "Failed to recover stuck message",
                        "Consumer: " + consumerGroup + ", Error: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Registers a reader delegate for watchdog monitoring.
     *
     * @param reader The reader delegate to track
     */
    void registerReader(ArtemisTopicReaderDelegate<T> reader) {
        readerDelegates.add(reader);
    }
    
    /**
     * Unregisters a reader delegate from watchdog monitoring.
     *
     * @param reader The reader delegate to remove
     */
    void unregisterReader(ArtemisTopicReaderDelegate<T> reader) {
        readerDelegates.remove(reader);
    }
    
    /**
     * Returns the claim timeout in seconds.
     *
     * @return Claim timeout (0 = disabled)
     */
    public int getClaimTimeoutSeconds() {
        return claimTimeoutSeconds;
    }
    
    protected Connection createConnection() throws JMSException {
        Connection connection = connectionFactory.createConnection();
        connection.start(); // Always start connection to allow consuming
        openConnections.add(connection);
        return connection;
    }
    
    protected void closeConnection(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (JMSException e) {
                log.warn("Error closing JMS connection");
            }
            openConnections.remove(connection);
        }
    }

    public String getTopicName() {
        return effectiveTopicName;
    }

    /**
     * Returns the base topic name (without runId suffix).
     * <p>
     * Used by readers to build unique subscription names that include the topic name,
     * preventing queue name collisions between different topics.
     *
     * @return Base topic name (e.g., "batch-topic")
     */
    public String getBaseTopicName() {
        return baseTopicName;
    }

    @Override
    protected ITopicReader<T, javax.jms.Message> createReaderDelegate(ResourceContext context) {
        return new ArtemisTopicReaderDelegate<>(this, context);
    }

    @Override
    protected ITopicWriter<T> createWriterDelegate(ResourceContext context) {
        return new ArtemisTopicWriterDelegate<>(this, context);
    }

    @Override
    protected UsageState getWriteUsageState() {
        // Lightweight check if we can create a session? 
        // Or simply assume ACTIVE if brokerStarted.
        // For now, assume ACTIVE if no recent errors.
        return UsageState.ACTIVE;
    }

    @Override
    protected UsageState getReadUsageState() {
        return UsageState.ACTIVE;
    }
    
    @Override
    protected void onSimulationRunSet(String simulationRunId) {
        // Physically isolate topics by runId to prevent consumers from reading
        // messages from a different simulation run.
        if (simulationRunId != null && !simulationRunId.trim().isEmpty()) {
            this.effectiveTopicName = this.baseTopicName + "_" + simulationRunId.trim();
            log.debug("Artemis topic for '{}' is now physically isolated for runId '{}'. Effective topic name: {}",
                baseTopicName, simulationRunId, effectiveTopicName);
        } else {
            this.effectiveTopicName = this.baseTopicName;
            log.debug("Artemis topic for '{}' is using base topic name: {}", baseTopicName, effectiveTopicName);
        }
    }

    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // Estimate Artemis memory usage based on the configured maxSizeBytes for the address,
        // which represents the max heap buffer before paging. Add a baseline for the broker itself.
        long brokerBaseline = 32L * 1024 * 1024; // ~32MB baseline for broker internals
        long estimatedBytes = brokerBaseline + this.maxSizeBytesForEstimation;

        return List.of(new MemoryEstimate(
            getResourceName(),
            estimatedBytes,
            "Artemis Broker (" + (brokerBaseline / 1024 / 1024) + "MB) + Topic Heap Buffer ("
                + (this.maxSizeBytesForEstimation / 1024 / 1024) + "MB)",
            MemoryEstimate.Category.TOPIC
        ));
    }

    // =========================================================================
    // Journal Retention API
    // =========================================================================

    /**
     * Returns whether journal retention is enabled for the embedded broker.
     * <p>
     * <b>Thread Safety:</b> Safe to call from any thread (delegates to
     * {@link EmbeddedBrokerProcess#isJournalRetentionEnabled()}).
     *
     * @return true if retention is enabled and replay is available
     */
    static boolean isJournalRetentionEnabled() {
        return EmbeddedBrokerProcess.isJournalRetentionEnabled();
    }

    /**
     * Returns the embedded Artemis server instance for management operations.
     * <p>
     * Used by reader delegates to query queue existence and trigger replay.
     * <p>
     * <b>Thread Safety:</b> Safe to call from any thread (delegates to
     * {@link EmbeddedBrokerProcess#getServer()}).
     *
     * @return the ActiveMQServer, or null if broker not started or external broker used
     */
    static ActiveMQServer getEmbeddedServer() {
        return EmbeddedBrokerProcess.getServer();
    }

    /**
     * Checks if a queue exists in the broker.
     * <p>
     * FAIL FAST: If we cannot determine queue existence, we throw an exception.
     * We must NOT guess - both wrong guesses lead to silent failures:
     * <ul>
     *   <li>Assuming "exists" when it doesn't → Consumer misses all historical messages</li>
     *   <li>Assuming "not exists" when it does → Consumer replays millions of messages unnecessarily</li>
     * </ul>
     * <p>
     * <b>Thread Safety:</b> Safe to call concurrently. Broker handles synchronization.
     *
     * @param queueName the internal queue name (format: "topicName::subscriptionName"), must not be null
     * @return true if queue exists, false if it definitely does not exist
     * @throws UnsupportedOperationException if external broker is used (JMX not implemented)
     * @throws RuntimeException if queue existence cannot be determined due to broker error
     */
    static boolean queueExistsInBroker(String queueName) {
        ActiveMQServer server = getEmbeddedServer();

        try {
            String[] queueNames;

            if (server != null) {
                // Embedded: Direct access via management control
                queueNames = server.getActiveMQServerControl().getQueueNames();
            } else {
                // External broker: Would require JMX - not implemented yet
                // For now, fail fast with clear error message
                throw new UnsupportedOperationException(
                    "Queue existence check for external broker not yet implemented. " +
                    "External broker support requires JMX configuration.");
            }

            // Debug: Log what we're looking for and what exists
            if (log.isDebugEnabled()) {
                log.debug("Checking for queue '{}' in broker. Existing queues: {}",
                    queueName, java.util.Arrays.toString(queueNames));
            }

            for (String existing : queueNames) {
                if (existing.equals(queueName)) {
                    return true;
                }
            }
            return false;

        } catch (UnsupportedOperationException e) {
            // Re-throw our own exception
            throw e;
        } catch (Exception e) {
            // FAIL FAST: We cannot guess.
            // - Wrong "exists" → Consumer misses all historical messages
            // - Wrong "not exists" → Consumer replays millions of messages unnecessarily
            // Both are silent failures that can go unnoticed for days.
            // Better: Fail loudly, user sees problem immediately.
            String errorMsg = String.format(
                "Cannot determine if queue '%s' exists in broker. " +
                "Broker query failed: %s. " +
                "Consumer cannot start safely - fix broker connectivity first.",
                queueName, e.getMessage());
            log.error(errorMsg);
            throw new RuntimeException(errorMsg, e);
        }
    }

    /**
     * Registers a subscription in the in-memory registry.
     * <p>
     * Returns true if this is the first time we've seen this subscription
     * in the current JVM lifetime. Used as fast-path before broker query.
     * <p>
     * <b>Thread Safety:</b> Safe to call concurrently (uses ConcurrentHashMap).
     *
     * @param topicName the topic name (address), must not be null
     * @param subscriptionName the subscription name, must not be null
     * @return true if newly registered (first time), false if already known
     */
    static boolean registerSubscriptionInMemory(String topicName, String subscriptionName) {
        String key = topicName + "::" + subscriptionName;
        return knownSubscriptions.add(key);
    }

    /**
     * Triggers replay of all retained messages from the journal to a queue.
     * <p>
     * This copies ALL messages from the retention journal that were sent to
     * the specified address into the target queue. Used when a new consumer
     * group is detected to give it all historical messages.
     * <p>
     * <b>Thread Safety:</b> Safe to call concurrently. Artemis handles
     * internal synchronization.
     *
     * @param addressName the address (topic) to replay from, must not be null
     * @param queueName the queue to replay into (the subscription name, e.g., "batch-topic_analytics_runId"), must not be null
     * @throws Exception if replay fails or broker is unavailable
     */
    static void triggerReplay(String addressName, String queueName) throws Exception {
        ActiveMQServer server = getEmbeddedServer();
        if (server == null) {
            log.warn("Cannot replay messages: embedded server not available. "
                + "New consumer group '{}' will NOT receive historical messages.", queueName);
            return;
        }

        log.debug("Replaying retained messages from address '{}' to queue '{}'...",
            addressName, queueName);

        long startTime = System.currentTimeMillis();

        // Replay all messages (no date filter, no message filter)
        // Parameters: start (null = beginning), end (null = now), address, target, filter (null = all)
        server.replay(
            null,           // start date: null = from beginning
            null,           // end date: null = until now
            addressName,    // source address (topic name)
            queueName,      // target queue (subscription name)
            null            // filter: null = replay ALL messages
        );

        long duration = System.currentTimeMillis() - startTime;
        log.debug("Replay completed for queue '{}' in {} ms", queueName, duration);
    }

    @Override
    public void close() throws Exception {
        // Stop watchdog first
        if (watchdog != null) {
            watchdog.shutdown();
            try {
                if (!watchdog.awaitTermination(5, TimeUnit.SECONDS)) {
                    watchdog.shutdownNow();
                }
            } catch (InterruptedException e) {
                watchdog.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.debug("Artemis watchdog stopped for '{}'", getResourceName());
        }
        
        super.close(); // Closes delegates (readers/writers)
        
        // Close all tracked connections - this triggers broker cleanup
        // Broker will log harmless warnings (AMQ222061, AMQ222107) about
        // cleaning up sessions, but no ERROR logs.
        for (Connection conn : openConnections) {
            try {
                conn.close();
            } catch (Exception e) {
                // Ignore connection close errors during shutdown
            }
        }
        openConnections.clear();
        readerDelegates.clear();
    }
}
