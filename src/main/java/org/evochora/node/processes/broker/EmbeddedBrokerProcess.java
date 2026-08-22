package org.evochora.node.processes.broker;

import java.util.Map;

import org.evochora.datapipeline.resources.broker.EmbeddedBrokerRegistry;
import org.evochora.node.processes.AbstractProcess;
import org.evochora.node.spi.IServiceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

/**
 * Node process that manages the lifecycle of an embedded ActiveMQ Artemis broker.
 * <p>
 * The broker itself and its Artemis configuration live in
 * {@link EmbeddedBrokerRegistry}; this class only ties one of them to the node's process model.
 * Several instances can run in the same JVM, each configured with its own InVM server-ID.
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
 * broker startup.
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

    @Override
    public void start() {
        if (!options.hasPath("enabled") || !options.getBoolean("enabled")) {
            log.info("Embedded broker (serverId={}) is disabled in configuration. Skipping startup.", serverId);
            return;
        }

        EmbeddedBrokerRegistry.ensureStarted(options);
    }

    @Override
    public void stop() {
        if (!EmbeddedBrokerRegistry.isBrokerStarted(serverId)) {
            return;
        }

        try {
            EmbeddedBrokerRegistry.stopBroker(serverId);
        } catch (Exception e) {
            log.error("Failed to stop embedded broker (serverId={})", serverId);
            throw new RuntimeException("Failed to stop embedded broker (serverId=" + serverId + ")", e);
        }
    }
}
