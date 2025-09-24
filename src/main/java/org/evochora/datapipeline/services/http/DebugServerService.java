package org.evochora.datapipeline.services.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import io.javalin.http.staticfiles.Location;
import org.evochora.datapipeline.services.AbstractService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * DataPipeline Service wrapper for the DebugServer HTTP functionality.
 * Serves static web renderer files and provides a simple API to fetch prepared ticks.
 * This service reads from a debug SQLite database and serves the web debugger interface.
 */
public class DebugServerService extends AbstractService {
    
    private static final Logger log = LoggerFactory.getLogger(DebugServerService.class);

    private Javalin app;
    private String jdbcUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private int port;
    private String dbPath;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private boolean isAutoPaused = false;
    private long lastStatusTime = 0;
    private double lastTPS = 0.0;
    private CompressionConfig compressionConfig;
    private String resolvedDbPath;

    /**
     * Configuration for compression settings.
     */
    public static class CompressionConfig {
        public final boolean enabled;
        public final String algorithm;
        
        public CompressionConfig(boolean enabled, String algorithm) {
            this.enabled = enabled;
            this.algorithm = algorithm;
        }
    }

    /**
     * Creates a new DebugServerService with the specified configuration.
     * 
     * @param options The configuration options from evochora.conf
     * @param resources The map of injected resources (unused by this service)
     */
    public DebugServerService(Config options, Map<String, List<Object>> resources) {
        super(options, resources);
        // Read from options sub-config
        Config serviceOptions = options.hasPath("options") ? options.getConfig("options") : options;
        
        this.port = serviceOptions.hasPath("port") ? serviceOptions.getInt("port") : 8080;
        this.dbPath = serviceOptions.hasPath("dbPath") ? serviceOptions.getString("dbPath") : "runs/debug.sqlite";
        
        // Parse compression config if available
        if (serviceOptions.hasPath("compression")) {
            Config compressionConfig = serviceOptions.getConfig("compression");
            boolean enabled = compressionConfig.hasPath("enabled") ? compressionConfig.getBoolean("enabled") : false;
            String algorithm = compressionConfig.hasPath("algorithm") ? compressionConfig.getString("algorithm") : "gzip";
            this.compressionConfig = new CompressionConfig(enabled, algorithm);
        } else {
            this.compressionConfig = new CompressionConfig(false, "gzip");
        }
        
        log.info("DebugServerService initialized - port: {}, dbPath: {}, compression: {}", 
                port, dbPath, compressionConfig.enabled ? compressionConfig.algorithm : "disabled");
    }

    @Override
    public void run() {
        try {
            startService();
        } catch (Exception e) {
            log.error("DebugServerService failed to start: {}", e.getMessage(), e);
        }
    }

    protected void startService() throws Exception {
        log.info("Started service: DebugServerService - port: {}, dbPath: {} (dynamic), compression: {}", 
                port, dbPath, compressionConfig.enabled ? compressionConfig.algorithm : "disabled");

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
                        // Check if there are any ticks at all
                        Long total = fetchTickCount();
                        if (total != null && total > 0) {
                            // There are ticks but not the requested one
                            ctx.status(HttpStatus.NOT_FOUND).result("{\"error\":\"Tick " + tick + " not found. Available ticks: 1 to " + total + "\", \"availableTicks\":{\"min\":1,\"max\":" + total + "}}"); 
                        } else {
                            // No ticks available at all
                            ctx.status(HttpStatus.NOT_FOUND).result("{\"error\":\"No simulation data available. Please ensure the pipeline is running and has processed some ticks.\"}"); 
                        }
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
                        
                        // NEU: Source-Daten für alle Organismen injizieren
                        injectSourceData(map);
                        
                        // Keep inlineValues intact; UI deduplicates identical spans so only the usage will be shown once
                        String out = objectMapper.writeValueAsString(map);
                        
                        // Apply gzip compression if enabled
                        if (compressionConfig != null && compressionConfig.enabled) {
                            byte[] compressed = gzipCompress(out);
                            ctx.header("Content-Encoding", "gzip");
                            ctx.contentType("application/json").result(compressed);
                        } else {
                            ctx.contentType("application/json").result(out);
                        }
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
            this.running.set(true);
        } catch (Exception e) {
            log.error("DebugServerService failed to start HTTP server on port {}: {}", port, e.getMessage());
            // Don't throw exception, just log error and mark as failed
            this.running.set(false);
            return;
        }
    }

    protected void stopService() throws Exception {
        log.info("Stopping DebugServerService...");
        
        if (this.app != null) {
            try { 
                this.app.stop(); 
                this.running.set(false);
                // Manual pause - mark as not auto-paused
                this.isAutoPaused = false;
            } catch (Exception ignore) {}
            this.app = null;
        }
        
        log.info("Stopped service: DebugServerService");
    }

    protected void pauseService() throws Exception {
        // HTTP server doesn't support pausing, just mark as paused
        isAutoPaused = true;
        log.info("Paused service: DebugServerService");
    }

    protected void resumeService() throws Exception {
        // HTTP server doesn't support pausing, just mark as resumed
        isAutoPaused = false;
        log.info("Resumed service: DebugServerService");
    }

    @Override
    public String getActivityInfo() {
        if (!running.get()) {
            return "stopped";
        }
        
        // Show dynamically resolved path
        try {
            String currentDbPath = resolveDatabasePath(dbPath);
            String dbInfo = currentDbPath.replace('/', '\\');
            return String.format("port:%d reading %s", port, dbInfo);
        } catch (Exception e) {
            String dbInfo = dbPath != null ? dbPath.replace('/', '\\') : "unknown";
            return String.format("port:%d reading %s (error)", port, dbInfo);
        }
    }

    /**
     * Resolves the database path. If a directory is provided, finds the newest SQLite file.
     * If a specific file is provided, uses that file.
     * 
     * @param dbPath The configured database path (file or directory)
     * @return The resolved database path
     * @throws IllegalArgumentException if the path is invalid or no SQLite files are found
     */
    private String resolveDatabasePath(String dbPath) {
        if (dbPath == null || dbPath.trim().isEmpty()) {
            throw new IllegalArgumentException("Database path cannot be null or empty");
        }
        
        // If it's already a JDBC URL, return as-is
        if (dbPath.startsWith("jdbc:sqlite:")) {
            return dbPath;
        }
        
        Path path = Paths.get(dbPath);
        
        // If it's a file that exists, use it directly
        if (Files.isRegularFile(path)) {
            return path.toAbsolutePath().toString();
        }
        
        // If it's a directory, find the newest SQLite file
        if (Files.isDirectory(path)) {
            try (Stream<Path> files = Files.list(path)) {
                Path newestSqliteFile = files
                    .filter(file -> file.toString().toLowerCase().endsWith(".sqlite"))
                    .filter(Files::isRegularFile)
                    .max(Comparator.comparing(file -> {
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                            return attrs.creationTime().toInstant();
                        } catch (Exception e) {
                            // Fallback to modification time if creation time fails
                            try {
                                return Files.getLastModifiedTime(file).toInstant();
                            } catch (Exception ex) {
                                return java.time.Instant.EPOCH; // Fallback to epoch
                            }
                        }
                    }))
                    .orElse(null);
                
                if (newestSqliteFile == null) {
                    throw new IllegalArgumentException("No SQLite files found in directory: " + path.toAbsolutePath());
                }
                
                log.debug("Found newest SQLite file: {}", newestSqliteFile.getFileName());
                return newestSqliteFile.toAbsolutePath().toString();
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to scan directory for SQLite files: " + path.toAbsolutePath(), e);
            }
        }
        
        // If it's neither a file nor a directory, treat it as a file path
        return path.toAbsolutePath().toString();
    }

    /**
     * Gets the current JDBC URL, resolving the database path dynamically if needed.
     * This ensures that if the configured path is a directory, we always use the newest SQLite file.
     * 
     * @return The current JDBC URL to use for database connections
     */
    private String getCurrentJdbcUrl() {
        // Always resolve the database path dynamically
        try {
            String currentDbPath = resolveDatabasePath(dbPath);
            if (currentDbPath.startsWith("jdbc:sqlite:")) {
                return currentDbPath;
            } else {
                return "jdbc:sqlite:" + Path.of(currentDbPath).toAbsolutePath();
            }
        } catch (Exception e) {
            log.warn("Failed to resolve current database path: {}", e.getMessage());
            throw new RuntimeException("Failed to resolve database path", e);
        }
    }

    public boolean isRunning() {
        return running.get();
    }
    
    public boolean isAutoPaused() {
        return isAutoPaused;
    }

    public int getPort() {
        return app != null ? app.port() : -1;
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

    private byte[] gzipCompress(String data) throws java.io.IOException {
        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
             java.util.zip.GZIPOutputStream gzipOut = new java.util.zip.GZIPOutputStream(baos)) {
            gzipOut.write(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            gzipOut.finish();
            return baos.toByteArray();
        }
    }

    private String fetchTickJson(long tick) {
        String currentJdbcUrl = getCurrentJdbcUrl();
        try (Connection conn = DriverManager.getConnection(currentJdbcUrl);
             PreparedStatement ps = conn.prepareStatement("SELECT tick_data_json FROM prepared_ticks WHERE tick_number = ?")) {
            ps.setLong(1, tick);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    // tick_data is stored as BLOB, so always read as bytes
                    byte[] data = rs.getBytes(1);
                    if (data != null) {
                        // Try to detect if data is compressed (GZIP magic bytes: 1f 8b)
                        if (data.length >= 2 && data[0] == (byte) 0x1f && data[1] == (byte) 0x8b) {
                            // Data is GZIP compressed, decompress it
                            try {
                                java.io.ByteArrayInputStream byteStream = new java.io.ByteArrayInputStream(data);
                                try (java.util.zip.GZIPInputStream gzipStream = new java.util.zip.GZIPInputStream(byteStream)) {
                                    return new String(gzipStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                                }
                            } catch (Exception e) {
                                log.debug("Failed to decompress GZIP data, trying as string: {}", e.getMessage());
                                return new String(data, java.nio.charset.StandardCharsets.UTF_8);
                            }
                        } else {
                            // Data is not compressed, read as string
                            return new String(data, java.nio.charset.StandardCharsets.UTF_8);
                        }
                    }
                }
                return null;
            }
        } catch (Exception e) {
            // Check if it's a "no such table" error - this means the database exists but is empty
            if (e.getMessage() != null && e.getMessage().contains("no such table")) {
                log.debug("Database exists but no prepared_ticks table found (debug-indexer not ready yet)");
            } else {
                log.warn("Failed to read tick {}: {}", tick, e.getMessage());
            }
            return null;
        }
    }

    private String fetchMetaJson() {
        String currentJdbcUrl = getCurrentJdbcUrl();
        try (Connection conn = DriverManager.getConnection(currentJdbcUrl);
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
        String currentJdbcUrl = getCurrentJdbcUrl();
        try (Connection conn = DriverManager.getConnection(currentJdbcUrl);
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM prepared_ticks")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                return 0L;
            }
        } catch (Exception e) {
            // Check if it's a "no such table" error - this means the database exists but is empty
            if (e.getMessage() != null && e.getMessage().contains("no such table")) {
                log.debug("Database exists but no prepared_ticks table found (debug-indexer not ready yet)");
                return 0L; // Return 0 instead of null for empty database
            } else {
                log.warn("Failed to read tick count: {}", e.getMessage());
                return null;
            }
        }
    }

    // NEU: Source-Daten automatisch in Tick-Response einbetten
    private void injectSourceData(java.util.Map<String, Object> map) {
        try {
            // Hole alle Organismen aus der Tick-Daten
            Object organismsObj = map.get("organismDetails");
            
            if (organismsObj instanceof java.util.Map<?, ?>) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> organisms = (java.util.Map<String, Object>) organismsObj;
                
                for (java.util.Map.Entry<String, Object> entry : organisms.entrySet()) {
                    String organismId = entry.getKey();
                    Object organismObj = entry.getValue();
                    
                    if (organismObj instanceof java.util.Map<?, ?>) {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> organism = (java.util.Map<String, Object>) organismObj;
                        
                        // Hole basicInfo und sourceView
                        Object basicInfoObj = organism.get("basicInfo");
                        Object sourceViewObj = organism.get("sourceView");
                        
                        if (basicInfoObj instanceof java.util.Map<?, ?> && sourceViewObj instanceof java.util.Map<?, ?>) {
                            @SuppressWarnings("unchecked")
                            java.util.Map<String, Object> basicInfo = (java.util.Map<String, Object>) basicInfoObj;
                            @SuppressWarnings("unchecked")
                            java.util.Map<String, Object> sourceView = (java.util.Map<String, Object>) sourceViewObj;
                            
                            String programId = (String) basicInfo.get("programId");
                            
                            // Keep original lines structure - no transformation needed
                            // The web debugger expects 'lines', not 'sourceLines'
                            
                            // Lade Source-Daten für alle Organismen mit programId
                            if (programId != null) {
                                String sourceData = fetchSourceDataFromDb(programId);
                                if (sourceData != null) {
                                    sourceView.put("allSources", sourceData);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to inject source data: {}", e.getMessage());
            e.printStackTrace();
        }
    }


    // NEU: Lädt Source-Daten aus der program_artifacts Tabelle (sources)
    private String fetchSourceDataFromDb(String programId) {
        String currentJdbcUrl = getCurrentJdbcUrl();
        try (Connection conn = DriverManager.getConnection(currentJdbcUrl);
             PreparedStatement ps = conn.prepareStatement("SELECT artifact_json FROM program_artifacts WHERE program_id = ?")) {
            ps.setString(1, programId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String artifactJson = rs.getString(1);
                    
                    // Parse artifactJson und extrahiere sources
                    try {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> artifact = objectMapper.readValue(artifactJson, java.util.Map.class);
                        
                        // Extrahiere sources Map
                        Object sourcesObj = artifact.get("sources");
                        if (sourcesObj instanceof java.util.Map<?, ?>) {
                            @SuppressWarnings("unchecked")
                            java.util.Map<String, Object> sources = (java.util.Map<String, Object>) sourcesObj;
                            
                            // Konvertiere zu Map<String, List<String>> für den Client
                            java.util.Map<String, java.util.List<String>> sourcesMap = new java.util.HashMap<>();
                            for (java.util.Map.Entry<String, Object> entry : sources.entrySet()) {
                                if (entry.getValue() instanceof java.util.List<?>) {
                                    @SuppressWarnings("unchecked")
                                    java.util.List<Object> lines = (java.util.List<Object>) entry.getValue();
                                    
                                    // Konvertiere zu List<String>
                                    java.util.List<String> stringLines = lines.stream()
                                        .map(Object::toString)
                                        .collect(java.util.stream.Collectors.toList());
                                    
                                    sourcesMap.put(entry.getKey(), stringLines);
                                }
                            }
                            
                            // Gib sources als JSON zurück
                            return objectMapper.writeValueAsString(sourcesMap);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse artifact JSON for program {}: {}", programId, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read source data for program {}: {}", programId, e.getMessage());
        }
        
        return null;
    }
}
