package org.evochora.node.processes.broker;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.core.remoting.impl.invm.TransportConstants;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.remoting.impl.invm.InVMAcceptorFactory;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.settings.impl.AddressFullMessagePolicy;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.evochora.node.processes.AbstractProcess;
import org.evochora.node.spi.IServiceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

import ch.qos.logback.classic.Level;

/**
 * Node process that manages the lifecycle of an embedded ActiveMQ Artemis broker.
 * <p>
 * Multiple instances of this process can run in the same JVM, each managing a
 * separate Artemis broker with a unique InVM server-ID ({@code vm://0}, {@code vm://1}, etc.).
 * This enables different broker configurations — for example, one broker for topics
 * with journal retention and another for queues without retention.
 * <p>
 * Broker instances are stored in a static registry keyed by server-ID, accessible
 * via {@link #getServer(int)} for management operations (queue settings, replay triggers,
 * address queries).
 * <p>
 * <strong>Startup ordering:</strong> The {@code pipeline} process declares
 * {@code require} dependencies on broker processes so that topological sorting places
 * them before {@code pipeline}. The Node constructs and starts each process
 * in dependency order, guaranteeing all brokers are running before ServiceManager
 * creates resources.
 * <p>
 * <strong>Shutdown ordering:</strong> Node stops processes in reverse topological order
 * (LIFO). Since {@code pipeline} depends on the broker processes, the pipeline
 * (and all its services/resources) stops first, then the brokers shut down cleanly.
 * <p>
 * <strong>Dual-mode deployment:</strong> When connecting to an external broker
 * ({@code brokerUrl = "tcp://..."}), set {@code enabled = false} to skip embedded
 * broker startup. Resources will get {@code null} from {@link #getServer(int)} and
 * fall back to broker defaults.
 * <p>
 * <strong>Thread Safety:</strong>
 * <ul>
 *   <li>{@code brokerLock}: Synchronizes broker startup and shutdown</li>
 *   <li>{@code brokerRegistry}: ConcurrentHashMap for lock-free reads</li>
 *   <li>{@code retentionRegistry}: ConcurrentHashMap for lock-free reads</li>
 * </ul>
 * <p>
 * <strong>Configuration:</strong>
 * <pre>
 * topic-broker {
 *   className = "org.evochora.node.processes.broker.EmbeddedBrokerProcess"
 *   options {
 *     enabled = true
 *     serverId = 0
 *     dataDirectory = ${pipeline.dataBaseDir}/topic-broker
 *     persistenceEnabled = true
 *     journalRetention { enabled = true, periodDays = 7, maxBytes = 0 }
 *   }
 * }
 * queue-broker {
 *   className = "org.evochora.node.processes.broker.EmbeddedBrokerProcess"
 *   options {
 *     enabled = true
 *     serverId = 1
 *     dataDirectory = ${pipeline.dataBaseDir}/queue-broker
 *     persistenceEnabled = true
 *     journalRetention { enabled = false }
 *   }
 * }
 * </pre>
 */
public class EmbeddedBrokerProcess extends AbstractProcess implements IServiceProvider {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedBrokerProcess.class);

    /** Registry of running broker instances, keyed by InVM server-ID. */
    private static final ConcurrentHashMap<Integer, EmbeddedActiveMQ> brokerRegistry = new ConcurrentHashMap<>();

    /** Tracks journal retention per broker. */
    private static final ConcurrentHashMap<Integer, Boolean> retentionRegistry = new ConcurrentHashMap<>();

    /** Synchronizes broker startup and shutdown to prevent races. */
    private static final Object brokerLock = new Object();

    /** The InVM server-ID for this broker instance. */
    private final int serverId;

    /**
     * Constructs a new EmbeddedBrokerProcess.
     *
     * @param processName  The name of this process instance from the configuration.
     * @param dependencies Dependencies injected by the Node (currently none required).
     * @param options      The configuration for the embedded broker.
     */
    public EmbeddedBrokerProcess(final String processName, final Map<String, Object> dependencies,
                                 final Config options) {
        super(processName, dependencies, options);
        this.serverId = options.hasPath("serverId") ? options.getInt("serverId") : 0;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Exposes this process instance so that dependent processes (e.g. {@code pipeline})
     * can declare a startup-order dependency via {@code require}. The pipeline process
     * does not use the injected object — the dependency exists solely to guarantee the
     * broker is running before resources connect.
     *
     * @return this process instance (non-null to satisfy the require contract)
     */
    @Override
    public Object getExposedService() {
        return this;
    }

    // =========================================================================
    // Static accessors — used by ArtemisTopicResource and ArtemisQueueResource
    // =========================================================================

    /**
     * Returns whether an embedded broker with the given server-ID has been started.
     * <p>
     * <b>Thread Safety:</b> Lock-free read via ConcurrentHashMap.
     *
     * @param serverId the InVM server-ID to check
     * @return true if the broker is running
     */
    public static boolean isBrokerStarted(int serverId) {
        return brokerRegistry.containsKey(serverId);
    }

    /**
     * Returns whether journal retention is enabled for the broker with the given server-ID.
     * <p>
     * <b>Thread Safety:</b> Safe to call from any thread (reads from ConcurrentHashMap).
     *
     * @param serverId the InVM server-ID of the broker
     * @return true if retention is enabled and replay is available
     */
    public static boolean isJournalRetentionEnabled(int serverId) {
        return retentionRegistry.getOrDefault(serverId, false);
    }

    /**
     * Returns the embedded Artemis server instance for the given server-ID.
     * <p>
     * Used by resources to query queue existence, trigger replay, or configure
     * address-specific settings at runtime.
     * <p>
     * <b>Thread Safety:</b> Safe to call from any thread.
     *
     * @param serverId the InVM server-ID of the broker
     * @return the ActiveMQServer, or null if the broker is not started or uses external transport
     */
    public static ActiveMQServer getServer(int serverId) {
        EmbeddedActiveMQ broker = brokerRegistry.get(serverId);
        if (broker != null) {
            return broker.getActiveMQServer();
        }
        return null;
    }

    /**
     * Parses the InVM server-ID from a broker URL.
     * <p>
     * For {@code vm://0} returns 0, for {@code vm://1} returns 1, etc.
     * For external broker URLs (e.g. {@code tcp://...}) returns -1.
     *
     * @param brokerUrl the broker URL to parse
     * @return the InVM server-ID, or -1 if not an InVM URL
     */
    public static int parseInVmServerId(String brokerUrl) {
        if (brokerUrl != null && brokerUrl.startsWith("vm://")) {
            try {
                return Integer.parseInt(brokerUrl.substring(5));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return -1;
    }

    // =========================================================================
    // Process lifecycle
    // =========================================================================

    @Override
    public void start() {
        if (!options.hasPath("enabled") || !options.getBoolean("enabled")) {
            log.info("Embedded broker (serverId={}) is disabled in configuration. Skipping startup.", serverId);
            return;
        }

        ensureStarted(options);
    }

    @Override
    public void stop() {
        if (!brokerRegistry.containsKey(serverId)) {
            return;
        }

        try {
            stopBroker(serverId);
        } catch (Exception e) {
            log.error("Failed to stop embedded broker (serverId={})", serverId);
            throw new RuntimeException("Failed to stop embedded broker (serverId=" + serverId + ")", e);
        }
    }

    // =========================================================================
    // Broker startup / shutdown (also used by tests via ensureStarted)
    // =========================================================================

    /**
     * Starts an embedded broker with the given configuration.
     * <p>
     * Idempotent: if a broker with the same server-ID is already running, this method
     * returns immediately. In production, called by {@link #start()}. Tests may call
     * this directly to start a broker without going through the Node process lifecycle.
     * <p>
     * Configuration keys (flat, no prefix):
     * <ul>
     *   <li>{@code serverId} - InVM server-ID (default: 0)</li>
     *   <li>{@code dataDirectory} - Data directory path</li>
     *   <li>{@code persistenceEnabled} - Journal persistence (default: true)</li>
     *   <li>{@code maxDiskUsage} - Max disk usage percentage (default: -1 = disabled)</li>
     *   <li>{@code journalRetention.*} - Kafka-like retention settings</li>
     *   <li>{@code addressSettings.*} - Default address settings</li>
     * </ul>
     *
     * @param config broker configuration (flat keys)
     */
    public static void ensureStarted(Config config) {
        int id = config.hasPath("serverId") ? config.getInt("serverId") : 0;

        synchronized (brokerLock) {
            if (brokerRegistry.containsKey(id)) {
                return;
            }

            try {
                configureLogging();

                log.info("Starting Embedded ActiveMQ Artemis Broker (serverId={})...", id);

                Configuration artemisConfig = new ConfigurationImpl();

                configurePersistence(config, artemisConfig);
                configureTransportAndSecurity(id, artemisConfig);
                configureDiskUsage(config, artemisConfig);
                boolean retentionEnabled = configureJournalRetention(id, config, artemisConfig);
                configureAddressSettings(config, artemisConfig);

                EmbeddedActiveMQ broker = new EmbeddedActiveMQ();
                broker.setConfiguration(artemisConfig);
                broker.start();

                brokerRegistry.put(id, broker);
                if (retentionEnabled) {
                    retentionRegistry.put(id, true);
                }
                log.info("Embedded ActiveMQ Artemis Broker (serverId={}) started successfully.", id);

            } catch (Exception e) {
                log.error("Failed to start Embedded Artemis Broker (serverId={})", id);
                throw new RuntimeException("Failed to start Embedded Artemis Broker (serverId=" + id + ")", e);
            }
        }
    }

    /**
     * Gracefully stops the embedded broker with the given server-ID.
     * <p>
     * Safe to call when the broker is already stopped (no-op).
     *
     * @param serverId the InVM server-ID of the broker to stop
     * @throws Exception if broker shutdown fails
     */
    public static void stopBroker(int serverId) throws Exception {
        synchronized (brokerLock) {
            EmbeddedActiveMQ broker = brokerRegistry.remove(serverId);
            if (broker != null) {
                try {
                    log.info("Stopping Embedded ActiveMQ Artemis Broker (serverId={})...", serverId);
                    broker.stop();
                    log.info("Embedded ActiveMQ Artemis Broker (serverId={}) stopped.", serverId);
                } finally {
                    retentionRegistry.remove(serverId);
                }
            }
        }
    }

    /**
     * Resets all broker state for testing purposes only.
     * <p>
     * Stops all running brokers and clears the registry.
     * <p>
     * <strong>WARNING:</strong> This method is intended exclusively for test cleanup.
     * Calling it in production will corrupt all active connections and sessions.
     *
     * @throws Exception if broker shutdown fails
     */
    public static void resetForTesting() throws Exception {
        synchronized (brokerLock) {
            for (int id : new ArrayList<>(brokerRegistry.keySet())) {
                EmbeddedActiveMQ broker = brokerRegistry.remove(id);
                if (broker != null) {
                    broker.stop();
                }
            }
            retentionRegistry.clear();
        }
    }

    // =========================================================================
    // Configuration helpers
    // =========================================================================

    private static void configurePersistence(Config config, Configuration artemisConfig) {
        boolean persistenceEnabled = config.hasPath("persistenceEnabled")
            ? config.getBoolean("persistenceEnabled")
            : true;
        artemisConfig.setPersistenceEnabled(persistenceEnabled);

        if (persistenceEnabled) {
            String dataDir = resolveDataDirectory(config);
            File dataDirFile = new File(dataDir);

            if (!dataDirFile.exists() && !dataDirFile.mkdirs()) {
                throw new RuntimeException("Cannot create data directory: " + dataDirFile.getAbsolutePath());
            }

            if (!dataDirFile.canWrite()) {
                throw new RuntimeException("Data directory is not writable: " + dataDirFile.getAbsolutePath());
            }

            artemisConfig.setJournalDirectory(dataDir + "/journal");
            artemisConfig.setBindingsDirectory(dataDir + "/bindings");
            artemisConfig.setLargeMessagesDirectory(dataDir + "/largemessages");
            artemisConfig.setPagingDirectory(dataDir + "/paging");
        }
    }

    /**
     * Configures InVM transport with the given server-ID and disables security/JMX.
     *
     * @param serverId     the InVM server-ID for the acceptor
     * @param artemisConfig the Artemis configuration to modify
     */
    private static void configureTransportAndSecurity(int serverId, Configuration artemisConfig) {
        Map<String, Object> params = new HashMap<>();
        params.put(TransportConstants.SERVER_ID_PROP_NAME, serverId);
        artemisConfig.addAcceptorConfiguration(
            new TransportConfiguration(InVMAcceptorFactory.class.getName(), params));
        artemisConfig.setSecurityEnabled(false);
        artemisConfig.setJMXManagementEnabled(false);
    }

    private static void configureDiskUsage(Config config, Configuration artemisConfig) {
        int maxDiskUsage = config.hasPath("maxDiskUsage")
            ? config.getInt("maxDiskUsage")
            : -1;
        artemisConfig.setMaxDiskUsage(maxDiskUsage);

        if (maxDiskUsage == -1) {
            log.debug("Artemis global maxDiskUsage is disabled. The broker will use all available disk space.");
        } else {
            log.debug("Artemis global maxDiskUsage is set to {}%", maxDiskUsage);
        }
    }

    /**
     * Configures journal retention for the broker.
     *
     * @param serverId     the InVM server-ID (for logging)
     * @param config       the broker configuration
     * @param artemisConfig the Artemis configuration to modify
     * @return true if journal retention was enabled
     */
    private static boolean configureJournalRetention(int serverId, Config config, Configuration artemisConfig) {
        boolean persistenceEnabled = config.hasPath("persistenceEnabled")
            ? config.getBoolean("persistenceEnabled")
            : true;

        boolean retentionEnabled = !config.hasPath("journalRetention.enabled")
            || config.getBoolean("journalRetention.enabled");

        if (retentionEnabled) {
            if (!persistenceEnabled) {
                log.warn("Journal retention requires persistenceEnabled=true. "
                    + "Retention will be DISABLED.");
                return false;
            } else {
                String dataDir = resolveDataDirectory(config);
                String retentionDir = config.hasPath("journalRetention.directory")
                    ? config.getString("journalRetention.directory")
                    : dataDir + "/history";

                File retentionDirFile = new File(retentionDir);
                if (!retentionDirFile.exists() && !retentionDirFile.mkdirs()) {
                    throw new RuntimeException(
                        "Cannot create journal retention directory: "
                        + retentionDirFile.getAbsolutePath());
                }

                int periodDays = config.hasPath("journalRetention.periodDays")
                    ? config.getInt("journalRetention.periodDays")
                    : 0;

                long maxBytes = config.hasPath("journalRetention.maxBytes")
                    ? config.getLong("journalRetention.maxBytes")
                    : 0;

                artemisConfig.setJournalRetentionDirectory(retentionDir);

                if (periodDays > 0) {
                    artemisConfig.setJournalRetentionPeriod(TimeUnit.DAYS, periodDays);
                }

                if (maxBytes > 0) {
                    artemisConfig.setJournalRetentionMaxBytes(maxBytes);
                }

                log.debug("Broker (serverId={}): Journal retention enabled: directory={}, periodDays={}, maxBytes={}",
                    serverId, retentionDir,
                    periodDays == 0 ? "unlimited" : periodDays,
                    maxBytes == 0 ? "unlimited" : maxBytes);

                return true;
            }
        } else {
            log.debug("Broker (serverId={}): Journal retention DISABLED (standard JMS behavior)", serverId);
            return false;
        }
    }

    private static void configureAddressSettings(Config config, Configuration artemisConfig) {
        AddressSettings addressSettings = new AddressSettings();

        long redeliveryDelay = config.hasPath("addressSettings.redeliveryDelayMs")
            ? config.getLong("addressSettings.redeliveryDelayMs")
            : 5000L;
        addressSettings.setRedeliveryDelay(redeliveryDelay);

        double redeliveryMultiplier = config.hasPath("addressSettings.redeliveryMultiplier")
            ? config.getDouble("addressSettings.redeliveryMultiplier")
            : 2.0;
        addressSettings.setRedeliveryMultiplier(redeliveryMultiplier);

        long maxRedeliveryDelay = config.hasPath("addressSettings.maxRedeliveryDelayMs")
            ? config.getLong("addressSettings.maxRedeliveryDelayMs")
            : 300000L;
        addressSettings.setMaxRedeliveryDelay(maxRedeliveryDelay);

        int maxDeliveryAttempts = config.hasPath("addressSettings.maxDeliveryAttempts")
            ? config.getInt("addressSettings.maxDeliveryAttempts")
            : 0;
        addressSettings.setMaxDeliveryAttempts(maxDeliveryAttempts);

        // Default: PAGE policy for topics (queues override per-address)
        addressSettings.setAddressFullMessagePolicy(AddressFullMessagePolicy.PAGE);

        long maxSizeBytes = config.hasPath("addressSettings.maxSizeBytes")
            ? config.getLong("addressSettings.maxSizeBytes")
            : 20L * 1024 * 1024;
        addressSettings.setMaxSizeBytes(maxSizeBytes);

        int pageSizeBytes = config.hasPath("addressSettings.pageSizeBytes")
            ? config.getInt("addressSettings.pageSizeBytes")
            : 100 * 1024 * 1024;
        addressSettings.setPageSizeBytes(pageSizeBytes);

        addressSettings.setRetroactiveMessageCount(-1);

        String deadLetterAddress = config.hasPath("addressSettings.deadLetterAddress")
            ? config.getString("addressSettings.deadLetterAddress")
            : "DLQ";
        addressSettings.setDeadLetterAddress(SimpleString.of(deadLetterAddress));

        String expiryAddress = config.hasPath("addressSettings.expiryAddress")
            ? config.getString("addressSettings.expiryAddress")
            : "ExpiryQueue";
        addressSettings.setExpiryAddress(SimpleString.of(expiryAddress));

        artemisConfig.addAddressSetting("#", addressSettings);

        log.debug("Artemis address settings: redelivery=[delay={}ms, multiplier={}, maxDelay={}ms], "
                + "maxAttempts={}, dlq={}, expiry={}",
            redeliveryDelay, redeliveryMultiplier, maxRedeliveryDelay,
            maxDeliveryAttempts, deadLetterAddress, expiryAddress);
    }

    private static String resolveDataDirectory(Config config) {
        return config.hasPath("dataDirectory")
            ? config.getString("dataDirectory")
            : System.getProperty("user.dir") + "/data/broker";
    }

    /**
     * Configures Artemis-related loggers to reduce noise.
     * <p>
     * Sets default log levels for Artemis loggers only if the user hasn't explicitly
     * configured them in the logging configuration.
     */
    static void configureLogging() {
        ch.qos.logback.classic.Logger artemisLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.apache.activemq.artemis");
        if (artemisLogger.getLevel() == null) {
            artemisLogger.setLevel(Level.WARN);
        }

        ch.qos.logback.classic.Logger serverLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.apache.activemq.artemis.core.server");
        if (serverLogger.getLevel() == null) {
            serverLogger.setLevel(Level.OFF);
        }

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
}
