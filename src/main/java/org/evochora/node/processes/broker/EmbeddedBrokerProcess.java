package org.evochora.node.processes.broker;

import java.io.File;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
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
 * Node process that manages the lifecycle of the embedded ActiveMQ Artemis broker.
 * <p>
 * The broker is JVM-wide infrastructure shared by all Artemis-based resources
 * ({@code ArtemisTopicResource}, {@code ArtemisQueueResource}). This process ensures
 * the broker is started before any resources are created and stopped after all resources
 * are closed.
 * <p>
 * Artemis in-VM transport ({@code vm://0}) only allows one broker per JVM. This class
 * manages that singleton instance and provides static accessors for resources that need
 * the underlying {@link ActiveMQServer} for management operations (queue settings,
 * replay triggers, address queries).
 * <p>
 * <strong>Startup ordering:</strong> The {@code pipeline} process declares
 * {@code require = { broker = "embedded-broker" }} so that topological sorting places
 * this process before {@code pipeline}. The Node constructs and starts each process
 * in dependency order, guaranteeing the broker is running before ServiceManager
 * creates resources.
 * <p>
 * <strong>Shutdown ordering:</strong> Node stops processes in reverse topological order
 * (LIFO). Since {@code pipeline} depends on {@code embedded-broker}, the pipeline
 * (and all its services/resources) stops first, then the broker shuts down cleanly.
 * <p>
 * <strong>Dual-mode deployment:</strong> When connecting to an external broker
 * ({@code brokerUrl = "tcp://..."}), set {@code enabled = false} to skip embedded
 * broker startup. Resources will get {@code null} from {@link #getServer()} and
 * fall back to broker defaults.
 * <p>
 * <strong>Thread Safety:</strong>
 * <ul>
 *   <li>{@code brokerLock}: Synchronizes broker startup and shutdown</li>
 *   <li>{@code brokerStarted}: AtomicBoolean for lock-free status reads</li>
 *   <li>{@code journalRetentionEnabled}: volatile for cross-thread visibility</li>
 * </ul>
 * <p>
 * <strong>Configuration:</strong>
 * <pre>
 * embedded-broker {
 *   className = "org.evochora.node.processes.broker.EmbeddedBrokerProcess"
 *   options {
 *     enabled = true
 *     dataDirectory = ${pipeline.dataBaseDir}/broker
 *     persistenceEnabled = true
 *     maxDiskUsage = -1
 *     journalRetention { enabled = true, periodDays = 7, maxBytes = 0 }
 *     addressSettings { redeliveryDelayMs = 5000, maxDeliveryAttempts = 0 }
 *   }
 * }
 * </pre>
 */
public class EmbeddedBrokerProcess extends AbstractProcess implements IServiceProvider {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedBrokerProcess.class);

    private static EmbeddedActiveMQ embeddedBroker;
    private static final AtomicBoolean brokerStarted = new AtomicBoolean(false);
    private static final Object brokerLock = new Object();

    /** Tracks whether journal retention is enabled. Set once during broker startup. */
    private static volatile boolean journalRetentionEnabled = false;

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
     * Returns whether the embedded broker has been started.
     * <p>
     * <b>Thread Safety:</b> Lock-free read via AtomicBoolean.
     *
     * @return true if the broker is running
     */
    public static boolean isBrokerStarted() {
        return brokerStarted.get();
    }

    /**
     * Returns whether journal retention is enabled for the embedded broker.
     * <p>
     * <b>Thread Safety:</b> Safe to call from any thread (reads volatile field).
     *
     * @return true if retention is enabled and replay is available
     */
    public static boolean isJournalRetentionEnabled() {
        return journalRetentionEnabled;
    }

    /**
     * Returns the embedded Artemis server instance for management operations.
     * <p>
     * Used by resources to query queue existence, trigger replay, or configure
     * address-specific settings at runtime.
     * <p>
     * <b>Thread Safety:</b> Safe to call from any thread.
     *
     * @return the ActiveMQServer, or null if broker not started or external broker used
     */
    public static ActiveMQServer getServer() {
        if (embeddedBroker != null && brokerStarted.get()) {
            return embeddedBroker.getActiveMQServer();
        }
        return null;
    }

    // =========================================================================
    // Process lifecycle
    // =========================================================================

    @Override
    public void start() {
        if (!options.hasPath("enabled") || !options.getBoolean("enabled")) {
            log.debug("Embedded broker is disabled in configuration. Skipping startup.");
            return;
        }

        ensureStarted(options);
    }

    @Override
    public void stop() {
        if (!brokerStarted.get()) {
            return;
        }

        try {
            stopBroker();
        } catch (Exception e) {
            log.error("Failed to stop embedded broker");
            throw new RuntimeException("Failed to stop embedded broker", e);
        }
    }

    // =========================================================================
    // Broker startup / shutdown (also used by tests via ensureStarted)
    // =========================================================================

    /**
     * Starts the embedded broker with the given configuration.
     * <p>
     * Idempotent: if the broker is already running, this method returns immediately.
     * In production, called by {@link #start()}. Tests may call this directly to
     * start the broker without going through the Node process lifecycle.
     * <p>
     * Configuration keys (flat, no prefix):
     * <ul>
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
        synchronized (brokerLock) {
            if (brokerStarted.get()) {
                return;
            }

            try {
                configureLogging();

                log.info("Starting Embedded ActiveMQ Artemis Broker...");

                Configuration artemisConfig = new ConfigurationImpl();

                configurePersistence(config, artemisConfig);
                configureTransportAndSecurity(artemisConfig);
                configureDiskUsage(config, artemisConfig);
                configureJournalRetention(config, artemisConfig);
                configureAddressSettings(config, artemisConfig);

                embeddedBroker = new EmbeddedActiveMQ();
                embeddedBroker.setConfiguration(artemisConfig);
                embeddedBroker.start();

                brokerStarted.set(true);
                log.info("Embedded ActiveMQ Artemis Broker started successfully.");

            } catch (Exception e) {
                log.error("Failed to start Embedded Artemis Broker");
                throw new RuntimeException("Failed to start Embedded Artemis Broker", e);
            }
        }
    }

    /**
     * Gracefully stops the embedded broker.
     * <p>
     * Safe to call when the broker is already stopped (no-op).
     *
     * @throws Exception if broker shutdown fails
     */
    public static void stopBroker() throws Exception {
        synchronized (brokerLock) {
            if (embeddedBroker != null) {
                try {
                    log.info("Stopping Embedded ActiveMQ Artemis Broker...");
                    embeddedBroker.stop();
                    log.info("Embedded ActiveMQ Artemis Broker stopped.");
                } finally {
                    embeddedBroker = null;
                    brokerStarted.set(false);
                    journalRetentionEnabled = false;
                }
            }
        }
    }

    /**
     * Resets the broker state for testing purposes only.
     * <p>
     * <strong>WARNING:</strong> This method is intended exclusively for test cleanup.
     * Calling it in production will corrupt all active connections and sessions.
     *
     * @throws Exception if broker shutdown fails
     */
    public static void resetForTesting() throws Exception {
        stopBroker();
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

    private static void configureTransportAndSecurity(Configuration artemisConfig) {
        artemisConfig.addAcceptorConfiguration(new TransportConfiguration(InVMAcceptorFactory.class.getName()));
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

    private static void configureJournalRetention(Config config, Configuration artemisConfig) {
        boolean persistenceEnabled = config.hasPath("persistenceEnabled")
            ? config.getBoolean("persistenceEnabled")
            : true;

        boolean retentionEnabled = !config.hasPath("journalRetention.enabled")
            || config.getBoolean("journalRetention.enabled");

        if (retentionEnabled) {
            if (!persistenceEnabled) {
                log.warn("Journal retention requires persistenceEnabled=true. "
                    + "Retention will be DISABLED.");
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

                journalRetentionEnabled = true;

                log.debug("Journal retention enabled: directory={}, periodDays={}, maxBytes={}",
                    retentionDir,
                    periodDays == 0 ? "unlimited" : periodDays,
                    maxBytes == 0 ? "unlimited" : maxBytes);
            }
        } else {
            log.debug("Journal retention DISABLED (standard JMS behavior)");
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
