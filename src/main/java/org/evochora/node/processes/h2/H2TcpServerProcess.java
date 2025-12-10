package org.evochora.node.processes.h2;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.evochora.node.processes.AbstractProcess;
import org.h2.tools.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

/**
 * A manageable process that runs the H2 TCP server to allow external database clients
 * (IntelliJ, DBeaver, etc.) to connect to the H2 database while the application is running.
 * <p>
 * This is an alternative to using {@code AUTO_SERVER=TRUE} in the JDBC URL, which is
 * incompatible with {@code DB_CLOSE_ON_EXIT=FALSE} required for graceful shutdown.
 * <p>
 * <strong>Security considerations:</strong>
 * <ul>
 *   <li>By default, only localhost connections are allowed ({@code tcpAllowOthers = false})</li>
 *   <li>Set {@code tcpAllowOthers = true} to enable remote access (security risk!)</li>
 *   <li>Consider disabling this process in production environments</li>
 * </ul>
 * <p>
 * <strong>Configuration example:</strong>
 * <pre>
 * h2-tcp-server {
 *   className = "org.evochora.node.processes.h2.H2TcpServerProcess"
 *   options {
 *     enabled = false              # Default: false (opt-in)
 *     tcpPort = 9092               # TCP port (default: 9092)
 *     tcpAllowOthers = false       # Allow remote connections (default: false)
 *   }
 * }
 * </pre>
 * <p>
 * <strong>Connecting from external clients:</strong>
 * <ul>
 *   <li>JDBC URL: {@code jdbc:h2:tcp://localhost:9092/./data/database/evochora} (relative to working directory)</li>
 *   <li>Username: {@code sa}</li>
 *   <li>Password: (empty)</li>
 * </ul>
 * <p>
 * <strong>Port conflicts:</strong> If the configured port is already in use, a WARN log
 * is emitted and the server start is skipped (no exception thrown).
 */
public class H2TcpServerProcess extends AbstractProcess {
    private static final Logger log = LoggerFactory.getLogger(H2TcpServerProcess.class);

    private Server tcpServer;

    /**
     * Constructs a new H2TcpServerProcess.
     *
     * @param processName The name of this process instance from the configuration.
     * @param dependencies Dependencies injected by the Node (currently none required).
     * @param options The configuration for this process, including TCP port and security settings.
     */
    public H2TcpServerProcess(final String processName, final Map<String, Object> dependencies, final Config options) {
        super(processName, dependencies, options);
    }

    @Override
    public void start() {
        // Check if TCP server is explicitly disabled (default: false = disabled)
        if (options.hasPath("enabled") && !options.getBoolean("enabled")) {
            log.debug("H2 TCP server is disabled in configuration. Skipping startup.");
            return;
        }

        if (tcpServer != null) {
            log.warn("H2 TCP server is already running.");
            return;
        }

        final int tcpPort = options.hasPath("tcpPort") 
            ? options.getInt("tcpPort") 
            : 9092;
        
        final boolean tcpAllowOthers = options.hasPath("tcpAllowOthers") 
            && options.getBoolean("tcpAllowOthers");

        // Build H2 TCP server arguments
        final List<String> args = new ArrayList<>();
        args.add("-tcpPort");
        args.add(String.valueOf(tcpPort));
        
        if (tcpAllowOthers) {
            args.add("-tcpAllowOthers");
        }

        try {
            tcpServer = Server.createTcpServer(args.toArray(new String[0]));
            tcpServer.start();

            // Build JDBC URL for connection info
            String jdbcUrl = buildJdbcUrl(tcpPort);
            String accessInfo = tcpAllowOthers ? "remote allowed" : "localhost only";
            
            log.info("H2 TCP Server started on port {} ({}) - Connect: {}", 
                tcpPort, accessInfo, jdbcUrl);

        } catch (final SQLException e) {
            // Check if error is port-related (graceful handling)
            if (isPortConflictError(e)) {
                log.warn("Port {} already in use - H2 TCP Server not started", tcpPort);
                return; // Silent skip
            }
            
            // Other errors are fatal
            log.error("Failed to start H2 TCP Server: {}", e.getMessage());
            throw new RuntimeException("Failed to start H2 TCP Server", e);
        }
    }

    /**
     * Builds the JDBC URL for connecting to the database via TCP.
     * Uses the database path from the pipeline configuration if available.
     *
     * @param tcpPort The TCP port the server is listening on.
     * @return JDBC URL string for external clients.
     */
    private String buildJdbcUrl(final int tcpPort) {
        // Try to get database path from options
        if (options.hasPath("database.jdbcUrl")) {
            String jdbcUrl = options.getString("database.jdbcUrl");
            
            // Extract database path from file-based JDBC URL
            // Example: jdbc:h2:/home/user/evochora/data/evochora;MODE=... -> /home/user/evochora/data/evochora
            if (jdbcUrl.startsWith("jdbc:h2:")) {
                String dbPath = jdbcUrl.substring(8); // Remove "jdbc:h2:"
                int semicolonIndex = dbPath.indexOf(';');
                if (semicolonIndex > 0) {
                    dbPath = dbPath.substring(0, semicolonIndex);
                }
                // Convert to TCP URL
                return "jdbc:h2:tcp://localhost:" + tcpPort + "/" + dbPath;
            }
        }
        
        // Fallback: use default path (relative to working directory)
        return "jdbc:h2:tcp://localhost:" + tcpPort + "/" + System.getProperty("user.dir") + "/data/database/evochora";
    }

    /**
     * Checks if the SQLException is related to port conflicts.
     *
     * @param e The SQLException to check.
     * @return true if the error is port-related, false otherwise.
     */
    private boolean isPortConflictError(final SQLException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        
        // H2 error messages for port conflicts
        message = message.toLowerCase();
        return message.contains("port") 
            || message.contains("bind") 
            || message.contains("address already in use");
    }

    @Override
    public void stop() {
        if (tcpServer != null) {
            tcpServer.stop();
            tcpServer = null;
            log.info("H2 TCP Server stopped.");
        }
    }
}

