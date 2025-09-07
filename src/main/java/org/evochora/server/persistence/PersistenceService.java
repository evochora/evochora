package org.evochora.server.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.evochora.server.IControllable;
import org.evochora.server.contracts.IQueueMessage;
import org.evochora.server.contracts.ProgramArtifactMessage;
import org.evochora.server.contracts.raw.RawTickState; // NEU
import org.evochora.runtime.Config;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.server.queue.ITickMessageQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import org.evochora.compiler.internal.LinearizedProgramArtifact;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Consumes raw messages from a queue and persists them to a per-run SQLite database.
 * This service runs in its own thread, decoupling the simulation engine from disk I/O.
 * It handles raw data for simulation resumption and program artifacts.
 */
public final class PersistenceService implements IControllable, Runnable {

    private static final Logger log = LoggerFactory.getLogger(PersistenceService.class);

    private final ITickMessageQueue queue;
    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean autoPaused = new AtomicBoolean(false);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile boolean needsRetryClose = false; // Flag to retry database closure
    private final Object connectionLock = new Object(); // Synchronization for database operations
    private final EnvironmentProperties envProps;
    private final int batchSize; // Configurable batch size

    private Connection connection;
    private Path dbFilePath;
    private final String jdbcUrlOverride;
    private String jdbcUrlInUse;
    private volatile long lastPersistedTick = -1L;
    private long startTime = System.currentTimeMillis(); // Added for TPS calculation
    
    // Simple TPS calculation - no complex timer tracking needed

    private PreparedStatement tickInsertStatement;
    private int batchCount = 0; // Count of ticks in current batch
    private int actualBatchCount = 0; // Count of actual batches executed

    public PersistenceService(ITickMessageQueue queue) {
        this(queue, null, null, 1000);
    }

    public PersistenceService(ITickMessageQueue queue, String jdbcUrlOverride) {
        this(queue, jdbcUrlOverride, null, 1000);
    }

    public PersistenceService(ITickMessageQueue queue, EnvironmentProperties envProps) {
        this(queue, null, envProps, 1000);
    }

    public PersistenceService(ITickMessageQueue queue, EnvironmentProperties envProps, int batchSize) {
        this(queue, null, envProps, batchSize);
    }

    public PersistenceService(ITickMessageQueue queue, String jdbcUrlOverride, EnvironmentProperties envProps, int batchSize) {
        this.queue = queue;
        this.jdbcUrlOverride = jdbcUrlOverride;
        this.envProps = envProps;
        this.batchSize = batchSize; // Use passed batch size
        this.thread = new Thread(this, "PersistenceService");
        this.thread.setDaemon(true);
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            setupDatabase();
            String dbInfo = jdbcUrlOverride != null ? jdbcUrlOverride : 
                           (dbFilePath != null ? dbFilePath.toString() : "unknown");
            log.info("PersistenceService: {}", dbInfo);
            thread.start();
        }
    }

        @Override
    public void pause() {
        // Manual pause always sets autoPaused to false
        // Auto-pause will set autoPaused to true before calling pause()
        autoPaused.set(false);
        
        // Set the pause flag - the service will pause after finishing current batch
        paused.set(true);
        // Manual pause
        log.debug("Manual pause flag set, service will pause after finishing current batch and close database");
    }

    @Override
    public void resume() { 
        paused.set(false); 
        autoPaused.set(false);
        // Don't start processing timer here - it will start when actual tick processing begins
    }

    @Override
    public void shutdown() {
        if (running.compareAndSet(true, false)) {
            long currentTick = getLastPersistedTick();
            double tps = calculateTPS();
            // Only log if called from the service thread
            if (Thread.currentThread().getName().equals("PersistenceService")) {
                log.info("PersistenceService: graceful termination tick:{} TPS:{}", currentTick, String.format("%.2f", tps));
            }
            
            // Immediately perform database cleanup and WAL checkpointing to prevent WAL/SHM file leaks
            // This ensures cleanup happens even if called from external threads (like CLI exit)
            try {
                log.debug("Performing immediate database cleanup during shutdown");
                // Execute any remaining batch operations before closing
                if (tickInsertStatement != null && batchCount % batchSize != 0) {
                    tickInsertStatement.executeBatch();
                    actualBatchCount++; // Increment actual batch count
                    log.debug("Executed final batch of {} ticks during shutdown", batchCount % batchSize);
                }
                closeQuietly();
                log.debug("Database cleanup completed during shutdown");
            } catch (Exception e) {
                log.warn("Error during shutdown database cleanup: {}", e.getMessage());
            }
            
            thread.interrupt();
            // Wait a bit for the thread to finish processing current messages
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    public void forceShutdown() {
        if (running.get()) {
            running.set(false);
            
            // Even for force shutdown, try to perform database cleanup to prevent WAL/SHM file leaks
            try {
                log.debug("Performing database cleanup during force shutdown");
                // Execute any remaining batch operations before closing
                if (tickInsertStatement != null && batchCount % batchSize != 0) {
                    tickInsertStatement.executeBatch();
                    actualBatchCount++; // Increment actual batch count
                    log.debug("Executed final batch of {} ticks during force shutdown", batchCount % batchSize);
                }
                closeQuietly();
                log.debug("Database cleanup completed during force shutdown");
            } catch (Exception e) {
                log.warn("Error during force shutdown database cleanup: {}", e.getMessage());
            }
            
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() { return running.get(); }

    @Override
    public boolean isPaused() { return paused.get(); }
    
    @Override
    public boolean isAutoPaused() { return autoPaused.get(); }

    public Path getDbFilePath() { return dbFilePath; }
    public long getLastPersistedTick() { return lastPersistedTick; }
    public long getCurrentTick() { return lastPersistedTick; }
    public String getJdbcUrl() { return jdbcUrlInUse; }
    public int getBatchCount() { return actualBatchCount; }
    public int getBatchSize() { return batchSize; }

    private double calculateTPS() {
        long currentTime = System.currentTimeMillis();
        long currentTick = getLastPersistedTick();
        
        if (currentTick <= 0 || startTime <= 0) {
            return 0.0;
        }
        
        long totalTime = currentTime - startTime;
        if (totalTime <= 0) {
            return 0.0;
        }
        
        // Simple calculation: ticks / time since start
        return (double) currentTick / (totalTime / 1000.0);
    }

    public String getStatus() {
        if (!running.get()) {
            return "stopped";
        }
        
        String dbInfo = jdbcUrlOverride != null ? jdbcUrlOverride : 
                       (dbFilePath != null ? dbFilePath.toString() : "unknown");
        long currentTick = getLastPersistedTick();
        double tps = calculateTPS();
        
        if (paused.get()) {
            String status = autoPaused.get() ? "auto-paused" : "paused";
            return String.format("%-12s %-8d %-8.2f %s", status, currentTick, tps, dbInfo);
        }
        
        return String.format("%-12s %-8d %-8.2f %s", "started", currentTick, tps, dbInfo);
    }
    
    private void resetTPS() {
        // lastStatusTime = 0; // Removed
        // lastStatusTick = 0; // Removed
        // lastTPS = 0.0; // Removed
    }

    /**
     * Creates an optimized database connection with PRAGMA settings.
     * This method is used both for initial setup and for reconnecting after connection loss.
     * 
     * @return A configured database connection with performance optimizations
     * @throws Exception if connection creation fails
     */
    private Connection createOptimizedConnection() throws Exception {
        String jdbcUrl;
        
        if (jdbcUrlOverride != null && !jdbcUrlOverride.isBlank()) {
            jdbcUrl = jdbcUrlOverride;
        } else {
            // Only create new database if we don't have one yet
            if (dbFilePath == null) {
                Path runsDir = Paths.get(Config.RUNS_DIRECTORY);
                Files.createDirectories(runsDir);
                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                // NEUER DATEINAME
                dbFilePath = runsDir.resolve("sim_run_" + ts + "_raw.sqlite");
            }
            jdbcUrl = "jdbc:sqlite:" + dbFilePath.toAbsolutePath();
        }
        
        // Create connection
        Connection conn = DriverManager.getConnection(jdbcUrl);
        
        // Apply performance optimizations
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL"); // Write-Ahead Logging for better concurrency
            st.execute("PRAGMA synchronous=NORMAL"); // Faster writes
            st.execute("PRAGMA cache_size=10000"); // Larger cache
            st.execute("PRAGMA temp_store=MEMORY"); // Use memory for temp tables
        }
        
        return conn;
    }

    private void setupDatabase() {
        try {
            connection = createOptimizedConnection();
            jdbcUrlInUse = jdbcUrlOverride != null ? jdbcUrlOverride : 
                          "jdbc:sqlite:" + dbFilePath.toAbsolutePath();
            
            try (Statement st = connection.createStatement()) {
                // NEUES SCHEMA
                st.execute("CREATE TABLE IF NOT EXISTS program_artifacts (program_id TEXT PRIMARY KEY, artifact_json TEXT)");
                st.execute("CREATE TABLE IF NOT EXISTS raw_ticks (tick_number INTEGER PRIMARY KEY, tick_data_json TEXT)");
                st.execute("CREATE TABLE IF NOT EXISTS simulation_metadata (key TEXT PRIMARY KEY, value TEXT)");
                try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO simulation_metadata (key, value) VALUES (?, ?)")) {
                    if (envProps != null) {
                        ps.setString(1, "worldShape");
                        ps.setString(2, objectMapper.writeValueAsString(envProps.getWorldShape()));
                        ps.executeUpdate();
                    }
                    ps.setString(1, "runMode");
                    ps.setString(2, "debug"); // Always run in debug mode
                    ps.executeUpdate();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to setup database", e);
        }
    }

    private void closeQuietly() {
        synchronized (connectionLock) {
            try {
                // Finalize any remaining batch operations
                if (tickInsertStatement != null) {
                    if (batchCount % batchSize != 0) { // Use batchSize here
                        tickInsertStatement.executeBatch();
                        actualBatchCount++; // Increment actual batch count
                    }
                    tickInsertStatement.close();
                    tickInsertStatement = null;
                }
                
                // Perform WAL checkpoint to ensure all data is written to main database
                // This prevents WAL and SHM files from being left behind
                if (connection != null && !connection.isClosed()) {
                    try (Statement st = connection.createStatement()) {
                        // Perform a FULL checkpoint to write all WAL data to main database
                        st.execute("PRAGMA wal_checkpoint(FULL)");
                        log.debug("WAL checkpoint completed - all data written to main database");
                    } catch (Exception e) {
                        // Skip WAL checkpoint if database is busy - this is normal with concurrent access
                        if (e.getMessage().contains("SQLITE_BUSY") || e.getMessage().contains("database is locked")) {
                            log.info("Skipping WAL checkpoint before closing due to concurrent access: {}", e.getMessage());
                        } else if (e.getMessage().contains("stmt pointer is closed") || e.getMessage().contains("database has been closed")) {
                            // Statement or database already closed is a real problem - data might be incomplete
                            log.error("Database or statement was already closed before WAL checkpoint - data may be incomplete! {}", e.getMessage());
                        } else {
                            log.warn("Error during WAL checkpoint before closing: {}", e.getMessage());
                        }
                    }
                }
                
                // Close the connection - this will properly release WAL and SHM files
                if (connection != null) {
                    connection.close();
                    connection = null;
                    log.debug("Database connection closed - WAL and SHM files should be released");
                }
            } catch (Exception e) {
                log.warn("Error during database cleanup: {}", e.getMessage());
            }
        }
    }

    @Override
    public void run() {
        try {
            while (running.get()) {
                if (paused.get()) {
                    // Paused
                    
                    // In pause mode: check periodically for new ticks
                    Thread.sleep(1000); // Check every second for new ticks
                    
                    // Check if there are new ticks to process
                    // BUT: only auto-resume if we're in auto-pause mode, not manual pause
                    if (queue.size() > 0 && autoPaused.get()) {
                        log.debug("New ticks detected in queue, waking up from auto-pause");
                        paused.set(false);
                        autoPaused.set(false);
                        
                        // Reopen database connection since it was closed during auto-pause
                        if (connection == null || connection.isClosed()) {
                            log.debug("Reopening database connection after auto-pause");
                            connection = createOptimizedConnection();
                            batchCount = 0;
                            actualBatchCount = 0; // Reset actual batch count
                            // Reset the prepared statement since we have a new connection
                            tickInsertStatement = null;
                        }
                        continue;
                    }
                    continue;
                }
                
                // Check if we need to reconnect after manual pause resume
                if (connection == null || connection.isClosed()) {
                    log.debug("Reconnecting to database after manual pause resume");
                    connection = createOptimizedConnection();
                    batchCount = 0;
                    actualBatchCount = 0; // Reset actual batch count
                    // Reset the prepared statement since we have a new connection
                    tickInsertStatement = null;
                }

                try {
                    // Process multiple messages in batch for better performance
                    List<IQueueMessage> batch = new ArrayList<>();
                    IQueueMessage msg = queue.poll(100, TimeUnit.MILLISECONDS); // Non-blocking poll
                    if (msg != null) {
                        // Start processing batch
                        
                        batch.add(msg);
                        // Collect more messages if available
                        while (batch.size() < batchSize && (msg = queue.poll()) != null) { // Use batchSize here
                            batch.add(msg);
                        }
                        
                        // Process batch
                        for (IQueueMessage batchMsg : batch) {
                            if (batchMsg instanceof ProgramArtifactMessage pam) {
                                try {
                                    handleProgramArtifact(pam);
                                } catch (Exception e) {
                                    if (running.get()) {
                                        log.warn("Failed to handle program artifact {}: {}", pam.programId(), e.getMessage());
                                    }
                                }
                            } else if (batchMsg instanceof RawTickState rts) {
                                try {
                                    // Check if we're still running before processing
                                    if (!running.get()) {
                                        break; // Exit early if shutting down
                                    }
                                    handleRawTick(rts);
                                } catch (Exception e) {
                                    if (running.get()) {
                                        log.warn("Failed to handle raw tick {}: {}", rts.tickNumber(), e.getMessage());
                                    }
                                }
                            }
                        }
                        
                        // Batch processing completed
                        
                        // Log batch processing
                        if (batch.size() > 0) {
                            log.debug("Processed batch of {} messages", batch.size());
                        }
                        
                        // After processing batch, check if we should pause
                        if (paused.get()) {
                            log.debug("Manual pause requested, entering pause mode after batch completion");
                            
                            // For manual pause: close database cleanly with SHM + WAL checkpointing
                            if (!autoPaused.get()) {
                                log.debug("Closing database cleanly for manual pause");
                                
                                // First, execute any remaining batch operations
                                if (tickInsertStatement != null && batchCount % batchSize != 0) {
                                    try {
                                        tickInsertStatement.executeBatch();
                                        actualBatchCount++; // Increment actual batch count
                                        log.debug("Executed final batch of {} ticks before pause", batchCount % batchSize);
                                    } catch (Exception e) {
                                        log.warn("Error executing final batch before pause: {}", e.getMessage());
                                    }
                                }
                                
                                // Perform WAL checkpoint to ensure all changes are in the main database
                                if (connection != null && !connection.isClosed()) {
                                    try (Statement st = connection.createStatement()) {
                                        // Perform a FULL checkpoint to write all WAL data to main database
                                        st.execute("PRAGMA wal_checkpoint(FULL)");
                                        log.debug("WAL checkpoint completed before manual pause - all data written to main database");
                                    } catch (Exception e) {
                                        // Skip WAL checkpoint if database is busy - this is normal with concurrent access
                                        if (e.getMessage().contains("SQLITE_BUSY") || e.getMessage().contains("database is locked")) {
                                            log.info("Skipping WAL checkpoint before pause due to concurrent access: {}", e.getMessage());
                                        } else if (e.getMessage().contains("stmt pointer is closed") || e.getMessage().contains("database has been closed")) {
                                            // Statement or database already closed is a real problem - data might be incomplete
                                            log.error("Database or statement was already closed before WAL checkpoint - data may be incomplete! {}", e.getMessage());
                                        } else {
                                            log.warn("Error during WAL checkpoint before pause: {}", e.getMessage());
                                        }
                                    }
                                }
                                
                                closeQuietly();
                            }
                            continue;
                        }
                    } else {
                        // No more ticks to process - auto-pause to save resources
                        log.debug("No more ticks in queue, auto-pausing persistence service");
                        
                        // For auto-pause: execute any remaining incomplete batches before pausing
                        // This ensures all data is committed and available to other threads
                        if (tickInsertStatement != null && batchCount % batchSize != 0) {
                            try {
                                int remainingCount = batchCount % batchSize;
                                int[] result = tickInsertStatement.executeBatch();
                                actualBatchCount++; // Increment actual batch count
                                log.debug("Executed final batch of {} ticks before auto-pause, result: {}", remainingCount, java.util.Arrays.toString(result));
                                tickInsertStatement.clearBatch();
                                batchCount = 0; // Reset batch counter after execution
                            } catch (Exception e) {
                                log.warn("Error executing final batch before auto-pause: {}", e.getMessage());
                            }
                        }
                        
                        // Enhanced auto-pause: ensure WAL is checkpointed and database is closed
                        // to prevent leaving WAL/SHM files behind
                        synchronized (connectionLock) {
                            if (connection != null && !connection.isClosed()) {
                                boolean checkpointSuccessful = false;
                                try (Statement st = connection.createStatement()) {
                                    // Perform a FULL checkpoint to write all WAL data to main database
                                    st.execute("PRAGMA wal_checkpoint(FULL)");
                                    log.debug("WAL checkpoint completed before auto-pause - all data written to main database");
                                    checkpointSuccessful = true;
                                } catch (Exception e) {
                                    // Skip WAL checkpoint if database is busy - this is normal with concurrent access
                                    if (e.getMessage().contains("SQLITE_BUSY") || e.getMessage().contains("database is locked")) {
                                        log.info("Skipping WAL checkpoint before auto-pause due to concurrent access: {}", e.getMessage());
                                        checkpointSuccessful = true; // Consider this successful for busy cases
                                    } else if (e.getMessage().contains("stmt pointer is closed") || e.getMessage().contains("database has been closed")) {
                                        // Statement or database already closed is a real problem - data might be incomplete
                                        log.error("Database or statement was already closed before WAL checkpoint - data may be incomplete! {}", e.getMessage());
                                    } else {
                                        log.warn("Error during WAL checkpoint before auto-pause: {}", e.getMessage());
                                    }
                                }
                                
                                // Only close database connection if checkpoint was successful or we're skipping due to busy
                                if (checkpointSuccessful) {
                                    // Check if this is an in-memory database
                                    boolean isInMemory = jdbcUrlInUse != null && jdbcUrlInUse.contains("mode=memory");
                                    if (isInMemory) {
                                        log.debug("Skipping database connection close for in-memory database during auto-pause");
                                    } else {
                                        connection.close();
                                        connection = null;
                                        log.debug("Database connection closed during auto-pause - WAL and SHM files should be released");
                                    }
                                } else {
                                    log.warn("WAL checkpoint failed, will retry database closure later");
                                    needsRetryClose = true; // Schedule retry for next cycle
                                }
                            }
                        } // End synchronized block
                        
                        // Auto-pause - mark as auto-paused and set pause flag
                        // Auto-pausing
                        autoPaused.set(true);
                        paused.set(true);
                        
                        // For auto-pause: database is now closed, will be reopened on resume
                        log.debug("Auto-pause: database closed, will reopen on resume");
                        
                        // Wait a bit before checking for new ticks
                        try {
                            Thread.sleep(1000); // Check every second
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        
                        // Retry database closure if needed
                        if (needsRetryClose) {
                            synchronized (connectionLock) {
                                if (connection != null && !connection.isClosed()) {
                                    log.debug("Retrying database closure after previous failure");
                                    try {
                                        closeQuietly();
                                        needsRetryClose = false;
                                        log.debug("Database closure retry successful");
                                    } catch (Exception e) {
                                        log.warn("Database closure retry failed: {}", e.getMessage());
                                    }
                                }
                            }
                        }
                        
                        // Check if new ticks are available (only if not manually paused)
                        if (queue.size() > 0 && !paused.get()) {
                            log.debug("New ticks available, resuming persistence service");
                            autoPaused.set(false);
                            paused.set(false);
                            
                            // Reopen database connection since it was closed during auto-pause
                            if (connection == null || connection.isClosed()) {
                                log.debug("Reopening database connection after auto-pause");
                                connection = createOptimizedConnection();
                                batchCount = 0;
                                actualBatchCount = 0; // Reset actual batch count
                                // Reset the prepared statement since we have a new connection
                                tickInsertStatement = null;
                            }
                            continue;
                        }
                        
                        continue;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                log.error("PersistenceService fatal error, terminating service: {}", e.getMessage());
            }
        } finally {
            // Log graceful termination from the service thread
            if (Thread.currentThread().getName().equals("PersistenceService")) {
                long currentTick = getLastPersistedTick();
                double tps = calculateTPS();
                log.info("PersistenceService: graceful termination tick:{} TPS:{}", currentTick, String.format("%.2f", tps));
            }
        }
    }

    private void handleProgramArtifact(ProgramArtifactMessage pam) throws Exception {
        // Konvertierung zu linearisiertem Format für Jackson-Serialisierung
        // Wir brauchen die EnvironmentProperties für die Linearisierung
        if (envProps == null) {
            throw new IllegalStateException(
                "Cannot serialize ProgramArtifact: EnvironmentProperties are required for coordinate linearization. " +
                "Ensure EnvironmentProperties are provided when creating PersistenceService."
            );
        }
        
        try {
            LinearizedProgramArtifact linearized = pam.programArtifact().toLinearized(envProps);
            String json = objectMapper.writeValueAsString(linearized);
            
            try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO program_artifacts(program_id, artifact_json) VALUES (?, ?)")) {
                ps.setString(1, pam.programId());
                ps.setString(2, json);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            log.warn("Failed to serialize program artifact {}: {}", pam.programId(), e.getMessage());
            throw e;
        }
    }

    private void handleRawTick(RawTickState rts) throws Exception {
        try {
            // Ensure database connection is available
            if (connection == null || connection.isClosed()) {
                log.debug("Database connection not available, reopening...");
                connection = createOptimizedConnection();
                // Reset the prepared statement since we have a new connection
                tickInsertStatement = null;
            }
            
            // Use batch insert for better performance
            String json = objectMapper.writeValueAsString(rts);
            
            // Use a single prepared statement for all ticks
            if (tickInsertStatement == null) {
                tickInsertStatement = connection.prepareStatement(
                    "INSERT OR REPLACE INTO raw_ticks(tick_number, tick_data_json) VALUES (?, ?)");
            }
            
            tickInsertStatement.setLong(1, rts.tickNumber());
            tickInsertStatement.setString(2, json);
            tickInsertStatement.addBatch();
            
            // Execute batch every 1000 ticks for optimal performance
            if (++batchCount % batchSize == 0) {
                int[] result = tickInsertStatement.executeBatch();
                actualBatchCount++; // Increment actual batch count
                log.debug("Executed batch of {} ticks, result: {}", batchSize, java.util.Arrays.toString(result));
                // Only clear batch if statement still exists (avoid race condition during shutdown)
                if (tickInsertStatement != null) {
                    tickInsertStatement.clearBatch();
                }
            }
            
            lastPersistedTick = rts.tickNumber();
            
            // Tick persisted
        } catch (Exception e) {
            log.warn("Failed to serialize raw tick {}: {}", rts.tickNumber(), e.getMessage());
            throw e;
        }
    }
}