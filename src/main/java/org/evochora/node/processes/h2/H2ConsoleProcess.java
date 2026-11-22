package org.evochora.node.processes.h2;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.evochora.node.processes.AbstractProcess;
import org.evochora.util.CauseChain;
import org.h2.tools.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

/**
 * A manageable process that runs the H2 database web console on a separate port.
 * The console provides a web-based interface for browsing and querying H2 databases.
 *
 * <p>Security considerations:</p>
 * <ul>
 *   <li>By default, only localhost connections are allowed</li>
 *   <li>Set {@code allowOthers = true} to enable remote access (security risk!)</li>
 *   <li>Consider disabling this process in production environments</li>
 * </ul>
 *
 * <p>Configuration example:</p>
 * <pre>
 * h2-console {
 *   className = "org.evochora.node.processes.h2.H2ConsoleProcess"
 *   options {
 *     enabled = true
 *     network {
 *       host = "localhost"
 *       port = 8082
 *     }
 *     allowOthers = false
 *     database = ${pipeline.resources.index-database.options}   # Required: its jdbcUrl
 *     useSSL = false
 *     trace = false
 *   }
 * }
 * </pre>
 *
 * <p>After starting, the console is accessible at {@code http://localhost:8082}
 * (or the configured host/port).</p>
 *
 * <p>Users must manually enter database connection details on first access:</p>
 * <ul>
 *   <li>JDBC URL: {@code database.jdbcUrl}, which the process writes into the log at startup</li>
 *   <li>Username: {@code sa}</li>
 *   <li>Password: (empty)</li>
 * </ul>
 */
public class H2ConsoleProcess extends AbstractProcess {
    private static final Logger LOGGER = LoggerFactory.getLogger(H2ConsoleProcess.class);

    private Server webServer;

    /**
     * Constructs a new H2ConsoleProcess.
     *
     * @param processName The name of this process instance from the configuration.
     * @param dependencies Dependencies injected by the Node (currently none required).
     * @param options The configuration for this process, including network settings, H2 options
     *                and {@code database.jdbcUrl}, the URL of the database the console is meant for.
     * @throws IllegalArgumentException if the console is enabled and {@code database.jdbcUrl} is missing.
     */
    public H2ConsoleProcess(final String processName, final Map<String, Object> dependencies, final Config options) {
        super(processName, dependencies, options);
        final boolean enabled = !options.hasPath("enabled") || options.getBoolean("enabled");
        if (enabled && !options.hasPath("database.jdbcUrl")) {
            throw new IllegalArgumentException("The H2 web console needs database.jdbcUrl; "
                + "reference.conf sets it to the index database");
        }
    }

    @Override
    public void start() {
        // Check if console is explicitly disabled
        if (options.hasPath("enabled") && !options.getBoolean("enabled")) {
            LOGGER.debug("H2 web console is disabled in configuration. Skipping startup.");
            return;
        }

        if (webServer != null) {
            LOGGER.warn("H2 web console is already running.");
            return;
        }

        final String host = options.hasPath("network.host") 
            ? options.getString("network.host") 
            : "localhost";
        final int port = options.hasPath("network.port") 
            ? options.getInt("network.port") 
            : 8082;

        // Build H2 server arguments
        final List<String> args = new ArrayList<>();
        args.add("-web");
        args.add("-webPort");
        args.add(String.valueOf(port));

        // Allow remote connections (security consideration!)
        if (options.hasPath("allowOthers") && options.getBoolean("allowOthers")) {
            args.add("-webAllowOthers");
            LOGGER.warn("H2 console allows remote connections - this is a security risk!");
        }

        // Enable SSL if configured
        if (options.hasPath("useSSL") && options.getBoolean("useSSL")) {
            args.add("-webSSL");
        }

        // Enable trace output
        if (options.hasPath("trace") && options.getBoolean("trace")) {
            args.add("-trace");
        }

        try {
            webServer = Server.createWebServer(args.toArray(new String[0]));
            webServer.start();

            final String consoleUrl = String.format("http://%s:%d", host, port);
            
            final Config dbConfig = options.getConfig("database");
            final String username = dbConfig.hasPath("username") ? dbConfig.getString("username") : "sa";
            final String password = dbConfig.hasPath("password") ? dbConfig.getString("password") : "";
            // The console connects to the database the node has open, so the settings the URL
            // carries after the path are not needed to log in
            final String jdbcUrl = dbConfig.getString("jdbcUrl");
            final int settings = jdbcUrl.indexOf(';');
            LOGGER.info("H2 web console: {} - JDBC URL: {}, user: {}, password: {}",
                consoleUrl,
                settings < 0 ? jdbcUrl : jdbcUrl.substring(0, settings),
                username,
                password.isEmpty() ? "(empty)" : "***");

        } catch (final SQLException e) {
            // Known ground: the port, the address or the database the console is configured for;
            // the causes say which
            LOGGER.error("Failed to start H2 web console: {}", CauseChain.messages(e));
            throw new RuntimeException("Failed to start H2 web console", e);
        }
    }
    
    @Override
    public void stop() {
        if (webServer != null) {
            webServer.stop();
            webServer = null;
            LOGGER.debug("H2 web console stopped.");
        }
    }
}

