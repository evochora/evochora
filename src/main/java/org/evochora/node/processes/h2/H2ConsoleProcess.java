package org.evochora.node.processes.h2;

import com.typesafe.config.Config;
import org.evochora.datapipeline.utils.PathExpansion;
import org.evochora.node.processes.AbstractProcess;
import org.h2.tools.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 *   <li>JDBC URL: {@code jdbc:h2:~/evochora/data/evochora}</li>
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
     * @param options The configuration for this process, including network settings and H2 options.
     */
    public H2ConsoleProcess(final String processName, final Map<String, Object> dependencies, final Config options) {
        super(processName, dependencies, options);
    }

    @Override
    public void start() {
        // Check if console is explicitly disabled
        if (options.hasPath("enabled") && !options.getBoolean("enabled")) {
            LOGGER.info("H2 web console is disabled in configuration. Skipping startup.");
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
            
            // Log startup with connection details if database config is provided
            if (options.hasPath("database")) {
                final Config dbConfig = options.getConfig("database");
                final String jdbcUrl = getJdbcUrl(dbConfig);
                final String username = dbConfig.hasPath("username") ? dbConfig.getString("username") : "sa";
                final String password = dbConfig.hasPath("password") ? dbConfig.getString("password") : "";
                
                LOGGER.info("H2 web console started at {} - Connect: JDBC URL: {}, User: {}, Password: {}",
                    consoleUrl,
                    jdbcUrl,
                    username,
                    password.isEmpty() ? "(empty)" : "***");
            } else {
                LOGGER.info("H2 web console started at {}", consoleUrl);
            }

        } catch (final SQLException e) {
            LOGGER.error("Failed to start H2 web console", e);
            throw new RuntimeException("Failed to start H2 web console", e);
        }
    }
    
    /**
     * Builds the JDBC URL from database configuration with path expansion.
     * Uses {@link PathExpansion} to resolve system properties and environment variables.
     */
    private String getJdbcUrl(final Config dbConfig) {
        if (dbConfig.hasPath("jdbcUrl")) {
            String jdbcUrl = dbConfig.getString("jdbcUrl");
            // Expand variables in JDBC URL (e.g., ${user.home})
            return PathExpansion.expandPath(jdbcUrl);
        }
        if (!dbConfig.hasPath("dataDirectory")) {
            return "jdbc:h2:~/evochora/data/evochora;MODE=PostgreSQL";
        }
        
        String dataDir = dbConfig.getString("dataDirectory");
        // Expand variables in data directory path
        dataDir = PathExpansion.expandPath(dataDir);
        
        return "jdbc:h2:" + dataDir + "/evochora;MODE=PostgreSQL";
    }

    @Override
    public void stop() {
        if (webServer != null) {
            webServer.stop();
            webServer = null;
            LOGGER.info("H2 web console stopped.");
        }
    }
}

