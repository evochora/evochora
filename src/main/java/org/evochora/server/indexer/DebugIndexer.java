package org.evochora.server.indexer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.internal.LinearizedProgramArtifact;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.server.IControllable;
import org.evochora.server.contracts.debug.PreparedTickState;
import org.evochora.server.contracts.raw.RawTickState;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DebugIndexer processes raw organism state data and generates debug information.
 * Uses helper classes for different responsibilities and includes a queuing system
 * for handling database unavailability without data loss.
 */
public class DebugIndexer implements IControllable, Runnable {

    private static final Logger log = LoggerFactory.getLogger(DebugIndexer.class);

    // Queue configuration
    private static final int MAX_QUEUE_SIZE = 1000; // Maximum ticks to queue in memory
    private final ConcurrentLinkedQueue<PreparedTickState> dataQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger(0);
    private final Thread queueProcessorThread;
    private final AtomicBoolean queueProcessorRunning = new AtomicBoolean(false);

    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean autoPaused = new AtomicBoolean(false);
    private final AtomicBoolean databaseHealthy = new AtomicBoolean(true);
    private long startTime = System.currentTimeMillis();
    private long nextTickToProcess = 0L; // Start at 0 to include tick 0
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String rawDbPath;
    private String debugDbPath;

    private final int batchSize; // Configurable batch size
    private EnvironmentProperties envProps; // Environment properties loaded from database

    private Map<String, ProgramArtifact> artifacts = new HashMap<>();
    
    // Helper classes
    private final ArtifactValidator artifactValidator = new ArtifactValidator();
    private final DatabaseManager databaseManager;
    private final SourceViewBuilder sourceViewBuilder;
    private final InstructionBuilder instructionBuilder;
    private final InternalStateBuilder internalStateBuilder;
    private final TickProcessor tickProcessor;

    public DebugIndexer(String rawDbPath, int batchSize) {
        this(rawDbPath, rawDbPath.replace("_raw.sqlite", "_debug.sqlite"), batchSize);
    }

    public DebugIndexer(String rawDbUrl, String debugDbUrl, int batchSize) {
        this.rawDbPath = rawDbUrl;
        this.debugDbPath = debugDbUrl;
        this.batchSize = batchSize;
        this.databaseManager = new DatabaseManager(debugDbUrl);
        this.sourceViewBuilder = new SourceViewBuilder();
        this.instructionBuilder = new InstructionBuilder();
        this.internalStateBuilder = new InternalStateBuilder();
        this.tickProcessor = new TickProcessor(objectMapper, artifactValidator, sourceViewBuilder, instructionBuilder, internalStateBuilder);
        this.thread = new Thread(this, "DebugIndexer");
        this.thread.setDaemon(true);
        
        // Initialize queue processor thread
        this.queueProcessorThread = new Thread(this::processQueuedData, "DebugIndexer-QueueProcessor");
        this.queueProcessorThread.setDaemon(true);
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            String rawDbName = rawDbPath.substring(rawDbPath.lastIndexOf('/') + 1);
            String debugDbName = debugDbPath.substring(debugDbPath.lastIndexOf('/') + 1);
            log.info("DebugIndexer: reading {} writing {}", rawDbName, debugDbName);
            
            try {
                databaseManager.setupDebugDatabase();
                databaseHealthy.set(true);
                log.info("DebugIndexer: database setup successful, starting processing thread");
            } catch (Exception e) {
                databaseHealthy.set(false);
                log.warn("Failed to setup debug database: {} - starting with queuing enabled", e.getMessage(), e);
                // Start with queuing enabled - no data loss
            }
            
            // Start both threads
            thread.start();
            queueProcessorThread.start();
            queueProcessorRunning.set(true);
            log.info("DebugIndexer started with health status: {}", getHealthStatus());
        }
    }

    @Override
    public void pause() {
        // Manual pause always sets autoPaused to false
        // Auto-pause will set autoPaused to true before calling pause()
        autoPaused.set(false);
        
        // Set the pause flag - the service will pause after finishing current batch
        paused.set(true);
        log.debug("Manual pause flag set, service will pause after finishing current batch and close database");
        
        // For manual pause: immediately close database to ensure WAL is properly flushed
        // This prevents WAL files from remaining open
        if (Thread.currentThread().getName().equals("DebugIndexer")) {
            // Called from service thread - close immediately
            log.debug("Manual pause from service thread - closing database immediately");
            databaseManager.closeQuietly();
        } else {
            // Called from external thread - signal immediate close
            log.debug("Manual pause from external thread - signaling immediate database close");
        }
    }

    @Override
    public void resume() {
        paused.set(false);
        autoPaused.set(false);
        
        // Reopen database connection if it was closed during manual pause
        try {
            if (!databaseManager.isConnectionAvailable()) {
                log.debug("Resuming from manual pause - reopening database connection");
                databaseManager.setupDebugDatabase();
                databaseHealthy.set(true);
                log.info("Database connection successfully reopened on resume");
            }
        } catch (Exception e) {
            databaseHealthy.set(false);
            log.error("Failed to reopen database connection on resume: {} - continuing in degraded mode (no database writes)", e.getMessage(), e);
        }
    }

    @Override
    public void shutdown() {
        if (running.compareAndSet(true, false)) {
            long currentTick = getLastProcessedTick();
            double tps = calculateTPS();
            // Only log if called from the service thread
            if (Thread.currentThread().getName().equals("DebugIndexer")) {
                log.info("DebugIndexer: graceful termination tick:{} TPS:{}", currentTick, String.format("%.2f", tps));
            }
            thread.interrupt();
            queueProcessorThread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isPaused() {
        return paused.get();
    }
    
    @Override
    public boolean isAutoPaused() {
        return autoPaused.get();
    }
    
    /**
     * Check if the database is healthy and available for operations.
     * @return true if database operations can be performed
     */
    public boolean isDatabaseHealthy() {
        return databaseHealthy.get();
    }
    
    /**
     * Get the current health status of the debug indexer.
     * @return A string describing the current health status
     */
    public String getHealthStatus() {
        if (!running.get()) {
            return "STOPPED";
        }
        if (paused.get()) {
            return "PAUSED";
        }
        if (!databaseHealthy.get()) {
            return "RUNNING_DEGRADED";
        }
        return "RUNNING_HEALTHY";
    }
    
    /**
     * Attempt to recover database health by reconnecting.
     * This method can be called externally to attempt recovery from degraded mode.
     * @return true if recovery was successful, false otherwise
     */
    public boolean attemptDatabaseRecovery() {
        if (databaseHealthy.get()) {
            log.info("Database is already healthy, no recovery needed");
            return true;
        }
        
        try {
            log.info("Attempting database recovery...");
            databaseManager.setupDebugDatabase();
            databaseHealthy.set(true);
            log.info("Database recovery successful - returning to full functionality");
            return true;
        } catch (Exception e) {
            log.error("Database recovery failed: {}", e.getMessage(), e);
            databaseHealthy.set(false);
            return false;
        }
    }

    private double calculateTPS() {
        long currentTime = System.currentTimeMillis();
        long currentTick = nextTickToProcess; // Use local counter instead of database query
        
        if (currentTick <= 0) {
            return 0.0;
        }
        
        // Calculate TPS since tick 0
        long timeDiff = currentTime - startTime;
        if (timeDiff > 0) {
            return (double) currentTick / (timeDiff / 1000.0);
        }
        
        return 0.0;
    }

    public String getStatus() {
        if (!running.get()) {
            return "stopped";
        }
        
        if (paused.get()) {
            try {
                long currentTick = getLastProcessedTick(); // Use last processed tick instead of next tick to process
                String rawDbInfo = rawDbPath != null ? rawDbPath.replace('/', '\\') : "unknown";
                String debugDbInfo = debugDbPath != null ? debugDbPath.replace('/', '\\') : "unknown";
                String status = autoPaused.get() ? "auto-paused" : "paused";
                return String.format("%s tick:%d reading %s writing %s", 
                        status, currentTick, rawDbInfo, debugDbInfo);
            } catch (Exception e) {
                return String.format("paused ERROR:%s", e.getMessage());
            }
        }
        
        try {
            long currentTick = getLastProcessedTick(); // Use last processed tick instead of next tick to process
            String rawDbInfo = rawDbPath != null ? rawDbPath.replace('/', '\\') : "unknown";
            String debugDbInfo = debugDbPath != null ? debugDbPath.replace('/', '\\') : "unknown";
            double tps = calculateTPS();
            
            return String.format("started tick:%d reading %s writing %s TPS:%.2f", 
                    currentTick, rawDbInfo, debugDbInfo, tps);
        } catch (Exception e) {
            return String.format("started ERROR:%s", e.getMessage());
        }
    }

    @Override
    public void run() {
        try {
            loadInitialData();
            // Start processing from tick 0 (nextTickToProcess = 0L from initialization)

            while (running.get()) {
                if (paused.get()) {
                    // In pause mode: check periodically for new ticks (only if auto-paused)
                    if (autoPaused.get()) {
                        try {
                            Thread.sleep(1000); // Check every second for new ticks
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        
                        // Check if there are new ticks to process (only if auto-paused)
                        if (hasNewTicksToProcess()) {
                            log.debug("New ticks detected, waking up from auto-pause");
                            autoPaused.set(false);
                            paused.set(false);
                            continue;
                        }
                    } else {
                        // Manually paused - ensure database is closed and just wait
                        if (databaseManager.isConnectionAvailable()) {
                            log.debug("Manual pause detected - ensuring database is closed");
                            databaseManager.closeQuietly();
                        }
                        try {
                            Thread.sleep(1000); // Check every second
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    continue;
                }
                
                // Check if we need to reopen database after manual pause
                if (!databaseManager.isConnectionAvailable()) {
                    log.debug("Reopening database connection after manual pause");
                    try {
                        databaseManager.setupDebugDatabase();
                    } catch (Exception e) {
                        log.warn("Failed to reopen database connection: {}", e.getMessage());
                    }
                }

                try {
                    int remainingTicks = processNextBatch();

                    if (remainingTicks > 0) {
                        // Continue processing immediately if we processed ticks
                        // No sleep - maximum throughput!
                        continue;
                    } else if (remainingTicks == 0) {
                        // remainingTicks == 0 means no more ticks are currently available
                        // But we need to check if new ticks arrived while we were processing
                        // If new ticks arrived, continue immediately; otherwise auto-pause
                        if (hasNewTicksToProcess()) {
                            // New ticks arrived while we were processing - continue immediately
                            log.debug("New ticks available after processing batch, continuing immediately");
                            continue;
                        } else {
                            // No more ticks available - auto-pause to save resources
                            log.debug("No more ticks to process, auto-pausing indexer");
                            
                            // For auto-pause: execute any remaining incomplete batches before pausing
                            // This ensures all data is committed and available to other threads
                            try {
                                databaseManager.executeRemainingBatch();
                            } catch (Exception e) {
                                log.warn("Error executing final batch before auto-pause: {}", e.getMessage());
                            }
                            
                            // Auto-pause - mark as auto-paused and set pause flag
                            autoPaused.set(true);
                            paused.set(true);
                            
                            // For auto-pause: keep database open for quick resume
                            log.debug("Auto-pause: keeping database open for quick resume");
                            
                            // Wait a bit before checking for new ticks
                            try {
                                Thread.sleep(1000); // Check every second
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                            
                            // Check if new ticks are available (only if not manually paused)
                            if (hasNewTicksToProcess() && !paused.get()) {
                                log.debug("New ticks available, resuming debug indexer");
                                autoPaused.set(false);
                                paused.set(false);
                                continue;
                            }
                            
                            continue;
                        }
                    } else {
                        // remainingTicks < 0 means there was an error, but ticks might still be available
                        // Check if there are truly no more ticks to process
                        if (hasNewTicksToProcess()) {
                            // There are still ticks, but they might be in a different batch
                            // Continue processing without pause
                            continue;
                        } else {
                            // No more ticks available - auto-pause to save resources
                            log.debug("No more ticks to process, auto-pausing indexer");
                            
                            // For auto-pause: execute any remaining incomplete batches before pausing
                            // This ensures all data is committed and available to other threads
                            try {
                                databaseManager.executeRemainingBatch();
                            } catch (Exception e) {
                                log.warn("Error executing final batch before auto-pause: {}", e.getMessage());
                            }
                            
                            // Auto-pause - mark as auto-paused and set pause flag
                            autoPaused.set(true);
                            paused.set(true);
                            
                            // For auto-pause: keep database open for quick resume
                            log.debug("Auto-pause: keeping database open for quick resume");
                            
                            // Wait a bit before checking for new ticks
                            try {
                                Thread.sleep(1000); // Check every second
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                            
                            // Check if new ticks are available (only if not manually paused)
                            if (hasNewTicksToProcess() && !paused.get()) {
                                log.debug("New ticks available, resuming debug indexer");
                                autoPaused.set(false);
                                paused.set(false);
                                continue;
                            }
                            
                            continue;
                        }
                    }
                } catch (Exception e) {
                    if (running.get()) { // Only warn if still running
                        log.warn("Failed to process tick {}: {}", nextTickToProcess, e.getMessage());
                    }
                }
                
                // Manual pause is now handled immediately in the pause() method
                // and in the main pause loop above
            }
            
            // Log health status periodically
            if (running.get() && !paused.get()) {
                String healthStatus = getHealthStatus();
                if (healthStatus.equals("RUNNING_DEGRADED")) {
                    log.warn("DebugIndexer health status: {} - continuing in degraded mode", healthStatus);
                }
            }
        } catch (Exception e) {
            log.error("DebugIndexer fatal error, terminating service: {}", e.getMessage());
        } finally {
            // Log graceful termination from the service thread
            if (Thread.currentThread().getName().equals("DebugIndexer")) {
                long currentTick = getLastProcessedTick(); // Use getter method
                double tps = calculateTPS();
                log.info("DebugIndexer: graceful termination tick:{} TPS:{}", currentTick, String.format("%.2f", tps));
            }
            
            // Cleanup database resources
            try {
                if (databaseManager.getBatchCount() > 0) {
                    databaseManager.executeRemainingBatch();
                    log.debug("Executed final batch during shutdown");
                }
                databaseManager.closeQuietly();
            } catch (Exception ignored) {}
        }
    }

    /**
     * Background thread method for processing queued data when database becomes available.
     */
    private void processQueuedData() {
        while (queueProcessorRunning.get() && !Thread.currentThread().isInterrupted()) {
            try {
                if (databaseHealthy.get() && !dataQueue.isEmpty()) {
                    // Process queued data
                    int processedCount = 0;
                    while (!dataQueue.isEmpty() && processedCount < 100) { // Process up to 100 per cycle
                        PreparedTickState tick = dataQueue.poll();
                        if (tick != null) {
                            try {
                                String json = objectMapper.writeValueAsString(tick);
                                databaseManager.writePreparedTick(tick.tickNumber(), json);
                                queueSize.decrementAndGet();
                                processedCount++;
                                log.debug("Processed queued tick {} from queue", tick.tickNumber());
                            } catch (Exception e) {
                                log.error("Failed to process queued tick {}: {}", tick.tickNumber(), e.getMessage());
                                // Put it back at the front of the queue to retry later
                                dataQueue.offer(tick);
                                queueSize.incrementAndGet();
                                break; // Stop processing this cycle if we hit an error
                            }
                        }
                    }
                    
                    if (processedCount > 0) {
                        log.info("Processed {} queued ticks, {} remaining in queue", processedCount, queueSize.get());
                    }
                }
                
                // Sleep before next cycle
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in queue processor: {}", e.getMessage(), e);
                try {
                    Thread.sleep(5000); // Wait longer on error
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Checks if there are new ticks available to process.
     * Used to wake up from pause mode when new ticks arrive.
     */
    private boolean hasNewTicksToProcess() {
        try (Connection rawConn = databaseManager.createConnection(rawDbPath);
             PreparedStatement countPs = rawConn.prepareStatement(
                     "SELECT COUNT(*) FROM raw_ticks WHERE tick_number >= ?")) {
            countPs.setLong(1, nextTickToProcess);
            try (ResultSet countRs = countPs.executeQuery()) {
                if (countRs.next()) {
                    return countRs.getLong(1) > 0;
                }
            }
        } catch (Exception e) {
            // Ignore errors when checking for new ticks
        }
        return false;
    }

    /**
     * Loads initial data from the raw database, including program artifacts and world configuration.
     * This method waits for the first tick to be available before loading program artifacts to ensure
     * all artifacts have been written by the persistence service.
     */
    private void loadInitialData() {
        // Wait for raw database to be available
        while (running.get()) {
            try (Connection rawConn = databaseManager.createConnection(rawDbPath);
                 Statement st = rawConn.createStatement()) {
                
                // First: Wait for raw_ticks table to exist and contain at least one tick
                // This ensures all program artifacts have been written by the persistence service
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM raw_ticks")) {
                    if (!rs.next() || rs.getLong(1) == 0) {
                        // No ticks available yet, wait and retry
                        log.info("Waiting for first tick to be available in raw database...");
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        continue;
                    }
                }
                
                // Second: Load program artifacts (now safe to do so)
                try (ResultSet rs = st.executeQuery("SELECT program_id, artifact_json FROM program_artifacts")) {
                    while (rs.next()) {
                        String id = rs.getString(1);
                        String json = rs.getString(2);
                        
                        // Deserialize to LinearizedProgramArtifact
                        LinearizedProgramArtifact linearized = objectMapper.readValue(json, LinearizedProgramArtifact.class);
                        
                        // Convert back to ProgramArtifact
                        ProgramArtifact artifact = linearized.toProgramArtifact();
                        this.artifacts.put(id, artifact);
                    }
                }
                
                // Third: Load world shape from database
                try (ResultSet rs = st.executeQuery("SELECT key, value FROM simulation_metadata WHERE key = 'worldShape'")) {
                    if (rs.next()) {
                        int[] dbWorldShape = objectMapper.readValue(rs.getString(2), int[].class);
                        // Also load isToroidal if available, otherwise assume true for backward compatibility
                        boolean isToroidal = true;
                        try (ResultSet toroidalRs = st.executeQuery("SELECT key, value FROM simulation_metadata WHERE key = 'isToroidal'")) {
                            if (toroidalRs.next()) {
                                isToroidal = objectMapper.readValue(toroidalRs.getString(2), Boolean.class);
                            }
                        }
                        this.envProps = new EnvironmentProperties(dbWorldShape, isToroidal);
                    }
                }
                
                // Fourth: Write all program artifacts to debug database
                writeProgramArtifacts();
                if (databaseHealthy.get()) {
                    log.info("Successfully wrote {} program artifacts to debug database", this.artifacts.size());
                }
                
                // Fifth: Copy simulation metadata to debug database
                writeSimulationMetadata();
                if (databaseHealthy.get()) {
                    log.info("Successfully wrote simulation metadata to debug database");
                }
                
                log.info("Successfully loaded initial data from raw database");
                break; // Successfully loaded, exit the loop
                
            } catch (Exception e) {
                if (running.get()) {
                    log.info("Waiting for raw database to be available: {}", e.getMessage());
                    try {
                        Thread.sleep(100); // Reduced from 1000ms to 100ms for faster response
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    break; // Service is shutting down
                }
            }
        }
    }

    /**
     * Writes simulation metadata to the debug database.
     * This method ensures that all simulation-specific metadata (like world shape, toroidal status, etc.)
     * are available in the debug database for the web debugger to access.
     * 
     * <p>Database failures are logged as errors but do not stop the process.
     */
    private void writeSimulationMetadata() {
        try (Connection conn = databaseManager.createConnection(debugDbPath);
             PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO simulation_metadata(key, value) VALUES (?, ?)")) {
            
            // World shape
            String worldShapeJson = objectMapper.writeValueAsString(envProps.getWorldShape());
            ps.setString(1, "worldShape");
            ps.setString(2, worldShapeJson);
            ps.addBatch();
            
            // Toroidal status
            String isToroidalJson = objectMapper.writeValueAsString(envProps.isToroidal());
            ps.setString(1, "isToroidal");
            ps.setString(2, isToroidalJson);
            ps.addBatch();
            
            // Execute batch
            int[] results = ps.executeBatch();
            log.debug("Executed {} batch operations for simulation metadata", results.length);
            ps.clearBatch();
        } catch (Exception e) {
            log.error("Failed to write simulation metadata to database: {} - marking database as unhealthy, future writes will be skipped", e.getMessage(), e);
            // Mark database as unhealthy and continue processing
            databaseHealthy.set(false);
        }
    }

    /**
     * Writes all loaded program artifacts to the debug database.
     * This method is called once after loading initial data and ensures that all program artifacts
     * are available in the debug database for the web debugger to access.
     * 
     * <p>Program artifacts are written as JSON strings using the program ID as the primary key.
     * If an artifact with the same ID already exists, it will be replaced (INSERT OR REPLACE).
     * 
     * <p>Individual artifact write failures are logged as warnings but do not stop the process.
     * If no artifacts are available, this method returns silently as this is normal for
     * simulations without compiled programs.
     * 
     * <p>Database failures are logged as errors but do not stop the process.
     */
    private void writeProgramArtifacts() {
        if (this.artifacts.isEmpty()) {
            log.debug("No program artifacts to write - this is normal for simulations without compiled programs");
            return;
        }
        
        try (Connection conn = databaseManager.createConnection(debugDbPath);
             PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO program_artifacts(program_id, artifact_json) VALUES (?, ?)")) {
            
            for (Map.Entry<String, ProgramArtifact> entry : this.artifacts.entrySet()) {
                try {
                    String json = objectMapper.writeValueAsString(entry.getValue());
                    ps.setString(1, entry.getKey());
                    ps.setString(2, json);
                    ps.executeUpdate();
                    log.debug("Wrote program artifact {} to debug database", entry.getKey());
                } catch (Exception e) {
                    // WARN: Individual artifact failed, continue with others
                    log.warn("Failed to write program artifact {}: {}", entry.getKey(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to write program artifacts to database: {} - marking database as unhealthy, future writes will be skipped", e.getMessage(), e);
            // Mark database as unhealthy and continue processing
            databaseHealthy.set(false);
        }
    }

    /**
     * Process next batch of ticks, up to the batch size limit.
     * If fewer ticks than batch size are available, process them anyway.
     * @return Number of ticks still remaining to be processed, or -1 for error
     */
    private int processNextBatch() {
        try (Connection rawConn = databaseManager.createConnection(rawDbPath)) {
            // Apply SQLite performance optimizations
            try (Statement stmt = rawConn.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
                stmt.execute("PRAGMA cache_size=10000");
                stmt.execute("PRAGMA temp_store=MEMORY");
            }
            
            // Count available ticks starting from the next one to process
            long totalAvailableTicks = 0;
            try (PreparedStatement countPs = rawConn.prepareStatement(
                    "SELECT COUNT(*) FROM raw_ticks WHERE tick_number >= ?")) {
                countPs.setLong(1, nextTickToProcess);
                try (ResultSet countRs = countPs.executeQuery()) {
                    if (countRs.next()) {
                        totalAvailableTicks = countRs.getLong(1);
                    }
                }
            } catch (Exception e) {
                log.warn("COUNT query failed: {}", e.getMessage());
                return -1; // Indicate error
            }
            
            // If we have no ticks remaining, return 0
            if (totalAvailableTicks == 0) {
                return 0;
            }
            
            // Process up to batch size ticks, but process fewer if that's all that's available
            // This ensures we don't wait for a full batch - we process whatever is available
            int actualBatchSize = Math.min((int)totalAvailableTicks, batchSize);
            
            log.debug("Processing {} ticks (available: {}, batch limit: {})", 
                     actualBatchSize, totalAvailableTicks, batchSize);
            
            // Log health status if database is in degraded mode
            if (!databaseHealthy.get()) {
                log.warn("Processing ticks in degraded mode - database operations are disabled");
            }
            
            try (PreparedStatement ps = rawConn.prepareStatement(
                    "SELECT tick_number, tick_data_json FROM raw_ticks WHERE tick_number >= ? ORDER BY tick_number LIMIT ?")) {
                ps.setLong(1, nextTickToProcess);
                ps.setInt(2, actualBatchSize);
                
                try (ResultSet rs = ps.executeQuery()) {
                    boolean processedAny = false;
                    int processedCount = 0;
                    
                    while (rs.next()) {
                        long tickNumber = rs.getLong(1);
                        String rawTickJson = rs.getString(2);
                        
                        try {
                            RawTickState rawTickState = objectMapper.readValue(rawTickJson, new TypeReference<>() {});
                            PreparedTickState preparedTick = tickProcessor.transformRawToPrepared(rawTickState, artifacts, envProps);
                            writePreparedTick(preparedTick);
                            
                            // Update next tick to process
                            if (tickNumber >= nextTickToProcess) {
                                nextTickToProcess = tickNumber + 1;
                            }
                            processedAny = true;
                            processedCount++;
                        } catch (Exception e) {
                            if (running.get()) {
                                log.warn("Failed to process tick {}: {}", tickNumber, e.getMessage());
                            }
                        }
                    }
                    
                    if (processedAny) {
                        log.debug("Processed {} ticks in batch, next tick to process: {}", processedCount, nextTickToProcess);
                    }
                    
                    // Calculate remaining ticks after processing
                    long remainingAfterProcessing = totalAvailableTicks - processedCount;
                    
                    // Return the number of ticks that are still remaining to be processed
                    return (int)remainingAfterProcessing;
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                log.warn("Failed to process next batch: {}", e.getMessage());
            }
            return -1; // Indicate error
        }
    }

    /**
     * Builds a complete source view for an organism, including source lines and annotations.
     * This method delegates to the SourceViewBuilder helper class.
     * 
     * @param organism The organism to build the source view for
     * @param artifact The program artifact containing source information
     * @param validity The validity status of the artifact
     * @return A complete source view, or null if the artifact is invalid
     */
    public PreparedTickState.SourceView buildSourceView(RawOrganismState organism, ProgramArtifact artifact, ArtifactValidator.ArtifactValidity validity) {
        return sourceViewBuilder.buildSourceView(organism, artifact, validity);
    }

    /**
     * Writes a prepared tick to the database or queues it if the database is unavailable.
     * Uses the queuing system to prevent data loss when database is down.
     */
    private void writePreparedTick(PreparedTickState preparedTick) {
        if (!databaseHealthy.get()) {
            // Database is down - queue the data
            if (queueSize.get() >= MAX_QUEUE_SIZE) {
                log.error("Queue is full ({} ticks) - stopping processing to prevent data loss", MAX_QUEUE_SIZE);
                // Stop processing to prevent data loss
                running.set(false);
                return;
            }
            
            dataQueue.offer(preparedTick);
            queueSize.incrementAndGet();
            log.warn("Database unavailable - queued tick {} (queue size: {})", preparedTick.tickNumber(), queueSize.get());
            return;
        }
        
        try {
            String json = objectMapper.writeValueAsString(preparedTick);
            databaseManager.writePreparedTick(preparedTick.tickNumber(), json);
        } catch (Exception e) {
            log.error("Failed to write prepared tick {} to database: {} - marking database as unhealthy, future writes will be skipped", preparedTick.tickNumber(), e.getMessage(), e);
            // Mark database as unhealthy and continue processing
            databaseHealthy.set(false);
            
            // Try to queue this tick as well
            if (queueSize.get() < MAX_QUEUE_SIZE) {
                dataQueue.offer(preparedTick);
                queueSize.incrementAndGet();
                log.warn("Queued tick {} after database failure (queue size: {})", preparedTick.tickNumber(), queueSize.get());
            } else {
                log.error("Queue is full - cannot queue tick {}, stopping processing to prevent data loss", preparedTick.tickNumber());
                running.set(false);
            }
        }
    }

    public long getLastProcessedTick() { 
        return nextTickToProcess > 0 ? nextTickToProcess - 1 : 0; 
    }
    
    public String getRawDbPath() { return rawDbPath; }
    public String getDebugDbPath() { return debugDbPath; }
    
    /**
     * Get current queue status for monitoring.
     * @return Current queue size
     */
    public int getQueueSize() {
        return queueSize.get();
    }
    
    /**
     * Get maximum queue capacity.
     * @return Maximum number of ticks that can be queued
     */
    public int getMaxQueueSize() {
        return MAX_QUEUE_SIZE;
    }
}