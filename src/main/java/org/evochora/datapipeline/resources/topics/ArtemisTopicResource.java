package org.evochora.datapipeline.resources.topics;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.remoting.impl.invm.InVMAcceptorFactory;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.evochora.datapipeline.api.memory.IMemoryEstimatable;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.topics.ITopicReader;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.Message;
import com.typesafe.config.Config;

import ch.qos.logback.classic.Level;

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
    
    // Shared Embedded Broker instance (Singleton per JVM)
    // The broker runs for the lifetime of the JVM - stopping it early causes ACK failures
    private static EmbeddedActiveMQ embeddedBroker;
    private static final AtomicBoolean brokerStarted = new AtomicBoolean(false);
    private static final Object brokerLock = new Object();
    
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
     *                  <li>{@code embedded.*} - Embedded broker configuration</li>
     *                </ul>
     */
    public ArtemisTopicResource(String name, Config options) {
        super(name, options);
        
        this.brokerUrl = options.hasPath("brokerUrl") ? options.getString("brokerUrl") : "vm://0";
        this.baseTopicName = options.hasPath("topicName") ? options.getString("topicName") : name;
        this.effectiveTopicName = this.baseTopicName; // Default to base name until runId is set
        this.claimTimeoutSeconds = options.hasPath("claimTimeout") ? options.getInt("claimTimeout") : 300;
        
        // Start embedded broker if configured and using In-VM transport
        if (brokerUrl.startsWith("vm://")) {
            ensureEmbeddedBrokerStarted(options);
        }
        
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
     * Ensures the shared embedded broker is started.
     * Thread-safe singleton initialization.
     */
    private void ensureEmbeddedBrokerStarted(Config options) {
        synchronized (brokerLock) {
            if (brokerStarted.get()) {
                return;
            }
            
            if (options.hasPath("embedded.enabled") && options.getBoolean("embedded.enabled")) {
                try {
                    // Configure Artemis logging BEFORE broker starts
                    // Only set defaults if user hasn't explicitly configured these loggers
                    configureArtemisLogging();
                    
                    log.info("Starting Embedded ActiveMQ Artemis Broker...");
                    
                    Configuration config = new ConfigurationImpl();
                    
                    // Persistence
                    boolean persistenceEnabled = options.hasPath("embedded.persistenceEnabled") 
                        ? options.getBoolean("embedded.persistenceEnabled") 
                        : true;
                    config.setPersistenceEnabled(persistenceEnabled);
                    
                    if (persistenceEnabled) {
                        String dataDir = options.hasPath("embedded.dataDirectory") 
                            ? options.getString("embedded.dataDirectory") 
                            : System.getProperty("user.dir") + "/data/topic";
                        
                        config.setJournalDirectory(dataDir + "/journal");
                        config.setBindingsDirectory(dataDir + "/bindings");
                        config.setLargeMessagesDirectory(dataDir + "/largemessages");
                        config.setPagingDirectory(dataDir + "/paging");
                    }
                    
                    // Transports
                    config.addAcceptorConfiguration(new TransportConfiguration(InVMAcceptorFactory.class.getName()));
                    
                    // Security (Simple for embedded)
                    config.setSecurityEnabled(false);
                    
                    // Disable JMX to reduce noise
                    config.setJMXManagementEnabled(false);
                    
                    // Address Settings for redelivery behavior (broker-side)
                    // These settings apply when a consumer connection is lost or transaction rolled back
                    // Complements the client-side watchdog for stuck threads
                    AddressSettings addressSettings = new AddressSettings();
                    
                    // Redelivery delay after connection loss or rollback (milliseconds)
                    long redeliveryDelay = options.hasPath("embedded.addressSettings.redeliveryDelayMs")
                        ? options.getLong("embedded.addressSettings.redeliveryDelayMs")
                        : 5000L; // 5 seconds default
                    addressSettings.setRedeliveryDelay(redeliveryDelay);
                    
                    // Exponential backoff multiplier for redelivery delay
                    double redeliveryMultiplier = options.hasPath("embedded.addressSettings.redeliveryMultiplier")
                        ? options.getDouble("embedded.addressSettings.redeliveryMultiplier")
                        : 2.0;
                    addressSettings.setRedeliveryMultiplier(redeliveryMultiplier);
                    
                    // Maximum redelivery delay (cap for exponential backoff)
                    long maxRedeliveryDelay = options.hasPath("embedded.addressSettings.maxRedeliveryDelayMs")
                        ? options.getLong("embedded.addressSettings.maxRedeliveryDelayMs")
                        : 300000L; // 5 minutes default
                    addressSettings.setMaxRedeliveryDelay(maxRedeliveryDelay);
                    
                    // Maximum delivery attempts before message goes to DLQ (0 = infinite)
                    int maxDeliveryAttempts = options.hasPath("embedded.addressSettings.maxDeliveryAttempts")
                        ? options.getInt("embedded.addressSettings.maxDeliveryAttempts")
                        : 0; // 0 = infinite (rely on application-level DLQ)
                    addressSettings.setMaxDeliveryAttempts(maxDeliveryAttempts);
                    
                    // Dead Letter Queue address - required to suppress broker warnings
                    String deadLetterAddress = options.hasPath("embedded.addressSettings.deadLetterAddress")
                        ? options.getString("embedded.addressSettings.deadLetterAddress")
                        : "DLQ";
                    addressSettings.setDeadLetterAddress(SimpleString.toSimpleString(deadLetterAddress));
                    
                    // Expiry Queue address - required to suppress broker warnings  
                    String expiryAddress = options.hasPath("embedded.addressSettings.expiryAddress")
                        ? options.getString("embedded.addressSettings.expiryAddress")
                        : "ExpiryQueue";
                    addressSettings.setExpiryAddress(SimpleString.toSimpleString(expiryAddress));
                    
                    // Apply settings to all addresses (# = wildcard)
                    config.addAddressSetting("#", addressSettings);
                    
                    log.debug("Artemis address settings: redelivery=[delay={}ms, multiplier={}, maxDelay={}ms], " +
                              "maxAttempts={}, dlq={}, expiry={}",
                        redeliveryDelay, redeliveryMultiplier, maxRedeliveryDelay, 
                        maxDeliveryAttempts, deadLetterAddress, expiryAddress);
                    
                    embeddedBroker = new EmbeddedActiveMQ();
                    embeddedBroker.setConfiguration(config);
                    embeddedBroker.start();
                    
                    brokerStarted.set(true);
                    log.info("Embedded ActiveMQ Artemis Broker started successfully.");
                    
                } catch (Exception e) {
                    log.error("Failed to start Embedded Artemis Broker");
                    throw new RuntimeException("Failed to start Embedded Artemis Broker", e);
                }
            }
        }
    }
    
    /**
     * Configures Artemis-related loggers to reduce noise.
     * <p>
     * Sets default log levels for Artemis loggers only if the user hasn't explicitly
     * configured them in the logging configuration. This ensures:
     * <ul>
     *   <li>Silent by default (no noisy INFO logs during startup)</li>
     *   <li>User can override in logging config if needed</li>
     * </ul>
     * <p>
     * The check {@code getLevel() == null} returns true when the logger inherits
     * its level from the parent (not explicitly configured).
     */
    private static void configureArtemisLogging() {
        // Artemis core logger - very verbose at INFO, set to WARN by default
        ch.qos.logback.classic.Logger artemisLogger = 
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.apache.activemq.artemis");
        if (artemisLogger.getLevel() == null) {
            artemisLogger.setLevel(Level.WARN);
        }
        
        // Artemis server logger - suppress harmless "AMQ224016: Caught exception" errors
        // during shutdown. These occur when ACKs arrive after consumer sessions are closed.
        // This is a race condition that's unavoidable with async ACKs.
        // Real fatal errors still throw exceptions that we catch.
        ch.qos.logback.classic.Logger serverLogger = 
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.apache.activemq.artemis.core.server");
        if (serverLogger.getLevel() == null) {
            serverLogger.setLevel(Level.OFF);
        }
        
        // Artemis audit loggers - extremely verbose, disable by default
        ch.qos.logback.classic.Logger auditBaseLogger = 
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.apache.activemq.audit.base");
        if (auditBaseLogger.getLevel() == null) {
            auditBaseLogger.setLevel(Level.OFF);
        }
        
        ch.qos.logback.classic.Logger auditMessageLogger = 
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.apache.activemq.audit.message");
        if (auditMessageLogger.getLevel() == null) {
            auditMessageLogger.setLevel(Level.OFF);
        }
        
        ch.qos.logback.classic.Logger auditResourceLogger = 
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.apache.activemq.audit.resource");
        if (auditResourceLogger.getLevel() == null) {
            auditResourceLogger.setLevel(Level.OFF);
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
        // Estimate Artemis memory usage
        // Embedded broker takes some heap (buffers, index)
        long estimatedBytes = 64L * 1024 * 1024; // ~64MB baseline
        
        return List.of(new MemoryEstimate(
            getResourceName(),
            estimatedBytes,
            "Artemis Embedded Broker + Buffers",
            MemoryEstimate.Category.TOPIC
        ));
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
