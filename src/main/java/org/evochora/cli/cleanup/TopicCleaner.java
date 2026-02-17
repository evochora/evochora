package org.evochora.cli.cleanup;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.remoting.impl.invm.InVMAcceptorFactory;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

/**
 * Cleans up Artemis topics/queues for simulation runs.
 * <p>
 * Topic naming: {baseTopic}_{runId}
 * Example: batch-topic_20260117-22042059-d59177fc-1eba-4ce8-85a6-36bd2032e3cb
 */
public class TopicCleaner {

    private static final Logger log = LoggerFactory.getLogger(TopicCleaner.class);

    /**
     * Pattern to extract runId from topic/queue names.
     * Matches: topicName_YYYYMMDD-HHmmssSS-UUID
     */
    private static final Pattern RUN_ID_PATTERN = Pattern.compile(".*_([0-9]{8}-[0-9]{8}-[a-f0-9-]{36})$");

    private final String dataDirectory;

    /**
     * Creates a new TopicCleaner.
     *
     * @param config application configuration
     */
    public TopicCleaner(Config config) {
        // Get broker data directory from embedded-broker process configuration
        if (config.hasPath("node.processes.embedded-broker.options.dataDirectory")) {
            this.dataDirectory = config.getString("node.processes.embedded-broker.options.dataDirectory");
        } else {
            // Fallback to default
            String dataBaseDir = config.getString("pipeline.dataBaseDir");
            this.dataDirectory = dataBaseDir + "/broker";
        }
    }

    /**
     * Lists all simulation run IDs that have topics.
     *
     * @return list of run IDs
     */
    public List<String> listRunIds() {
        Set<String> runIds = new HashSet<>();

        EmbeddedActiveMQ broker = null;
        try {
            broker = startBrokerForManagement();
            ActiveMQServer server = broker.getActiveMQServer();

            // Get all address names
            String[] addressNames = server.getActiveMQServerControl().getAddressNames();

            for (String addressName : addressNames) {
                String runId = extractRunId(addressName);
                if (runId != null) {
                    runIds.add(runId);
                }
            }
        } catch (Exception e) {
            log.error("Failed to list topic addresses: {}", e.getMessage());
        } finally {
            stopBroker(broker);
        }

        return new ArrayList<>(runIds);
    }

    /**
     * Performs cleanup on Artemis topics/queues.
     *
     * @param toKeep run IDs to keep
     * @param toDelete run IDs to delete
     * @param force if true, actually delete; if false, dry-run only
     * @param out output writer for progress
     * @return statistics with kept and deleted counts
     */
    public CleanupService.AreaStats cleanup(List<String> toKeep, List<String> toDelete, boolean force, PrintWriter out) {
        out.printf("Topics (%s):%n", dataDirectory);

        int keepCount = 0;
        int deleteCount = 0;

        EmbeddedActiveMQ broker = null;
        try {
            broker = startBrokerForManagement();
            ActiveMQServer server = broker.getActiveMQServer();

            // Get all address names and their queues
            String[] addressNames = server.getActiveMQServerControl().getAddressNames();

            // Group addresses by runId
            for (String addressName : addressNames) {
                String runId = extractRunId(addressName);
                if (runId == null) {
                    continue; // Skip system addresses
                }

                if (toKeep.contains(runId)) {
                    out.printf("  %s KEEP   %s%n", "\u2713", addressName);
                    keepCount++;
                } else if (toDelete.contains(runId)) {
                    if (force) {
                        try {
                            // First destroy all queues on this address
                            String[] queueNames = server.getActiveMQServerControl().getQueueNames();
                            for (String queueName : queueNames) {
                                if (queueName.contains(runId)) {
                                    server.destroyQueue(SimpleString.of(queueName));
                                }
                            }
                            // Then delete the address itself via management control
                            server.getActiveMQServerControl().deleteAddress(addressName);
                            out.printf("  %s DELETE %s (deleted)%n", "\u2717", addressName);
                            deleteCount++;
                        } catch (Exception e) {
                            out.printf("  %s DELETE %s (FAILED: %s)%n", "\u2717", addressName, e.getMessage());
                            log.error("Failed to delete address {}: {}", addressName, e.getMessage());
                        }
                    } else {
                        out.printf("  %s DELETE %s%n", "\u2717", addressName);
                        deleteCount++;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to cleanup topics: {}", e.getMessage());
            out.printf("  ERROR: %s%n", e.getMessage());
        } finally {
            stopBroker(broker);
        }

        out.println();
        return new CleanupService.AreaStats(keepCount, deleteCount);
    }

    /**
     * Starts a temporary broker instance for management operations.
     * This is necessary because we need to read/modify the Artemis journal.
     *
     * @return the embedded broker
     * @throws Exception if broker fails to start
     */
    private EmbeddedActiveMQ startBrokerForManagement() throws Exception {
        Configuration config = new ConfigurationImpl();
        config.setPersistenceEnabled(true);
        config.setJournalDirectory(dataDirectory + "/journal");
        config.setBindingsDirectory(dataDirectory + "/bindings");
        config.setLargeMessagesDirectory(dataDirectory + "/largemessages");
        config.setPagingDirectory(dataDirectory + "/paging");
        config.setSecurityEnabled(false);
        config.setJMXManagementEnabled(false);
        config.addAcceptorConfiguration(new TransportConfiguration(InVMAcceptorFactory.class.getName()));

        // Suppress Artemis logging during cleanup
        suppressArtemisLogging();

        EmbeddedActiveMQ broker = new EmbeddedActiveMQ();
        broker.setConfiguration(config);
        broker.start();

        return broker;
    }

    /**
     * Stops the broker gracefully.
     *
     * @param broker the broker to stop (may be null)
     */
    private void stopBroker(EmbeddedActiveMQ broker) {
        if (broker != null) {
            try {
                broker.stop();
            } catch (Exception e) {
                log.debug("Error stopping broker: {}", e.getMessage());
            }
        }
    }

    /**
     * Extracts run ID from a topic/queue name.
     *
     * @param name the topic or queue name
     * @return the run ID, or null if not a simulation topic
     */
    private String extractRunId(String name) {
        Matcher matcher = RUN_ID_PATTERN.matcher(name);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * Suppress Artemis logging during cleanup operations.
     */
    private void suppressArtemisLogging() {
        ch.qos.logback.classic.Logger artemisLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.apache.activemq.artemis");
        artemisLogger.setLevel(ch.qos.logback.classic.Level.ERROR);

        ch.qos.logback.classic.Logger serverLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.apache.activemq.artemis.core.server");
        serverLogger.setLevel(ch.qos.logback.classic.Level.OFF);

        // Suppress verbose audit logging
        ch.qos.logback.classic.Logger auditLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.apache.activemq.audit");
        auditLogger.setLevel(ch.qos.logback.classic.Level.OFF);
    }
}
