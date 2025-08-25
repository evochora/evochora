package org.evochora.server.http;

import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import io.javalin.http.staticfiles.Location;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Lightweight web service serving static web renderer files and a simple API to fetch prepared ticks.
 */
public final class DebugServer {
    private static final Logger log = LoggerFactory.getLogger(DebugServer.class);

    private Javalin app;
    private String jdbcUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private int port;
    private String dbPath;
    private boolean isRunning = false;
    private boolean isAutoPaused = false;
    private long lastStatusTime = 0;
    private double lastTPS = 0.0;

    public void start(String dbPath, int port) {
        try {
            this.dbPath = dbPath;
            this.port = port;
            
            // Prüfe, ob es eine JDBC-URL oder ein Dateipfad ist
            if (dbPath.startsWith("jdbc:sqlite:")) {
                this.jdbcUrl = dbPath;  // Direkt verwenden
            } else {
                // Behandle es als Dateipfad
                this.jdbcUrl = "jdbc:sqlite:" + Path.of(dbPath).toAbsolutePath();
            }
        } catch (Exception e) {
            log.error("DebugServer failed to start, invalid configuration: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid dbPath: " + dbPath);
        }

        try {
            this.app = Javalin.create(config -> {
                config.staticFiles.add(staticFiles -> {
                    staticFiles.hostedPath = "/";
                    String staticDir = Path.of(System.getProperty("user.dir"), "webdebugger").toAbsolutePath().toString();
                    staticFiles.directory = staticDir;
                    staticFiles.location = Location.EXTERNAL;
                    staticFiles.precompress = false;
                });
            });

            this.app.get("/api/tick/{tick}", ctx -> {
                try {
                    String tickStr = ctx.pathParam("tick");
                    long tick;
                    try { 
                        tick = Long.parseLong(tickStr); 
                    } catch (NumberFormatException nfe) { 
                        ctx.status(HttpStatus.BAD_REQUEST).result("Invalid tick"); 
                        return; 
                    }
                    
                    String json = fetchTickJson(tick);
                    if (json == null) { 
                        ctx.status(HttpStatus.NOT_FOUND).result("{}"); 
                        return; 
                    }
                    
                    try {
                        Long total = fetchTickCount();
                        java.util.Map<String, Object> map = objectMapper.readValue(json, new TypeReference<java.util.Map<String, Object>>() {});
                        map.put("totalTicks", total != null ? total : 0L);
                        // Remove ISA mapping if present
                        Object wm = map.get("worldMeta");
                        if (wm instanceof java.util.Map<?, ?> wmMap) {
                            ((java.util.Map<?, ?>) wmMap).remove("isaMap");
                        }
                        // Keep inlineValues intact; UI deduplicates identical spans so only the usage will be shown once
                        String out = objectMapper.writeValueAsString(map);
                        ctx.contentType("application/json").result(out);
                    } catch (Exception e) {
                        log.warn("Failed to augment tick json for tick {}: {}", tick, e.getMessage());
                        ctx.contentType("application/json").result(json);
                    }
                } catch (Exception e) {
                    log.warn("Failed to handle tick request: {}", e.getMessage());
                    ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).result("Internal server error");
                }
            });

            this.app.start(port);
            this.isRunning = true;
            // Show full path including directory like getStatus method does, using backslashes for consistency
            String dbInfo = dbPath != null ? dbPath.replace('/', '\\') : "unknown";
            log.info("DebugServer: port:{} reading {}", port, dbInfo);
        } catch (Exception e) {
            log.error("DebugServer failed to start HTTP server: {}", e.getMessage());
            throw new RuntimeException("Failed to start HTTP server", e);
        }
    }

    public void stop() {
        if (this.app != null) {
            try { 
                this.app.stop(); 
                this.isRunning = false;
                // Manual pause - mark as not auto-paused
                this.isAutoPaused = false;
                // Log graceful termination from the service thread
                if (Thread.currentThread().getName().equals("DebugServer")) {
                    log.info("DebugServer: graceful termination");
                }
            } catch (Exception ignore) {}
            this.app = null;
        }
    }
    
    public void forceStop() {
        if (this.app != null) {
            try { 
                this.app.stop(); 
            } catch (Exception ignore) {}
            this.app = null;
        }
    }

    public boolean isRunning() {
        return isRunning;
    }
    
    public boolean isAutoPaused() {
        return isAutoPaused;
    }

    public String getStatus() {
        if (!isRunning) {
            return "stopped";
        }
        
        // Show full path including directory like DebugIndexer does, using backslashes for consistency
        String dbInfo = dbPath != null ? dbPath.replace('/', '\\') : "unknown";
        return String.format("started port:%d reading %s", port, dbInfo);
    }
    
    private double calculateTPS() {
        long currentTime = System.currentTimeMillis();
        
        if (lastStatusTime == 0) {
            lastStatusTime = currentTime;
            return 0.0;
        }
        
        long timeDiff = currentTime - lastStatusTime;
        if (timeDiff > 0) {
            lastTPS = 1000.0 / timeDiff; // Requests per second
        }
        
        return lastTPS;
    }
    
    private void resetTPS() {
        lastStatusTime = 0;
        lastTPS = 0.0;
    }

    private String fetchTickJson(long tick) {
        try (Connection conn = DriverManager.getConnection(this.jdbcUrl);
             PreparedStatement ps = conn.prepareStatement("SELECT tick_data_json FROM prepared_ticks WHERE tick_number = ?")) {
            ps.setLong(1, tick);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
                return null;
            }
        } catch (Exception e) {
            log.warn("Failed to read tick {}: {}", tick, e.getMessage());
            return null;
        }
    }

    private String fetchMetaJson() {
        try (Connection conn = DriverManager.getConnection(this.jdbcUrl);
             PreparedStatement ps = conn.prepareStatement("SELECT key, value FROM simulation_metadata WHERE key IN ('worldShape','runMode')")) {
            try (ResultSet rs = ps.executeQuery()) {
                java.util.Map<String, Object> map = new java.util.HashMap<>();
                while (rs.next()) {
                    String key = rs.getString(1);
                    String val = rs.getString(2);
                    map.put(key, val);
                }
                // Wrap and return as JSON
                ObjectMapper om = objectMapper;
                java.util.Map<String, Object> out = new java.util.HashMap<>();
                if (map.containsKey("worldShape")) out.put("worldShape", objectMapper.readValue((String) map.get("worldShape"), int[].class));
                if (map.containsKey("runMode")) out.put("runMode", map.get("runMode"));
                return om.writeValueAsString(out);
            }
        } catch (Exception e) {
            log.warn("Failed to read meta: {}", e.getMessage());
            return null;
        }
    }

    private Long fetchTickCount() {
        try (Connection conn = DriverManager.getConnection(this.jdbcUrl);
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM prepared_ticks")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                return 0L;
            }
        } catch (Exception e) {
            log.warn("Failed to read tick count: {}", e.getMessage());
            return null;
        }
    }
}


