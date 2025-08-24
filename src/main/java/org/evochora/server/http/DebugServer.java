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

    public void start(String dbFilePath, int port) {
        try {
            this.jdbcUrl = "jdbc:sqlite:" + Path.of(dbFilePath).toAbsolutePath();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid dbFilePath: " + dbFilePath, e);
        }

        this.app = Javalin.create(config -> {
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                String staticDir = Path.of(System.getProperty("user.dir"), "webrenderer").toAbsolutePath().toString();
                staticFiles.directory = staticDir;
                staticFiles.location = Location.EXTERNAL;
                staticFiles.precompress = false;
            });
        });

        this.app.get("/api/tick/{tick}", ctx -> {
            String tickStr = ctx.pathParam("tick");
            long tick;
            try { tick = Long.parseLong(tickStr); } catch (NumberFormatException nfe) { ctx.status(HttpStatus.BAD_REQUEST).result("Invalid tick"); return; }
            String json = fetchTickJson(tick);
            if (json == null) { ctx.status(HttpStatus.NOT_FOUND).result("{}"); return; }
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
                log.warn("Failed to augment tick json: {}", e.getMessage());
                ctx.contentType("application/json").result(json);
            }
        });

        this.app.start(port);
        log.info("DebugServer started at http://localhost:{} using DB {}", port, dbFilePath);
    }

    public void stop() {
        if (this.app != null) {
            try { this.app.stop(); } catch (Exception ignore) {}
            this.app = null;
        }
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


