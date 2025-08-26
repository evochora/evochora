package org.evochora.server.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.evochora.server.IControllable;
import org.evochora.server.contracts.IQueueMessage;
import org.evochora.server.contracts.ProgramArtifactMessage;
import org.evochora.server.contracts.raw.RawTickState; // NEU
import org.evochora.runtime.Config;
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
    private final int[] worldShape;
    private final int batchSize; // Configurable batch size

    private Connection connection;
    private Path dbFilePath;
    private final String jdbcUrlOverride;
    private String jdbcUrlInUse;
    private volatile long lastPersistedTick = -1L;
    private long startTime = System.currentTimeMillis(); // Added for TPS calculation

    private PreparedStatement tickInsertStatement;
    private int batchCount = 0;

    public PersistenceService(ITickMessageQueue queue) {
        this(queue, null, null, 1000);
    }

    public PersistenceService(ITickMessageQueue queue, String jdbcUrlOverride) {
        this(queue, jdbcUrlOverride, null, 1000);
    }

    public PersistenceService(ITickMessageQueue queue, int[] worldShape) {
        this(queue, null, worldShape, 1000);
    }

    public PersistenceService(ITickMessageQueue queue, int[] worldShape, int batchSize) {
        this(queue, null, worldShape, batchSize);
    }

    public PersistenceService(ITickMessageQueue queue, String jdbcUrlOverride, int[] worldShape, int batchSize) {
        this.queue = queue;
        this.jdbcUrlOverride = jdbcUrlOverride;
        this.worldShape = worldShape != null ? java.util.Arrays.copyOf(worldShape, worldShape.length) : null;
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
        log.debug("Manual pause flag set, service will pause after finishing current batch and close database");
    }

    @Override
    public void resume() { 
        paused.set(false); 
        autoPaused.set(false);
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
            thread.interrupt();
            // Wait a bit for the thread to finish processing current messages
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            closeQuietly();
        }
    }
    
    public void forceShutdown() {
        if (running.get()) {
            running.set(false);
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

    private double calculateTPS() {
        long currentTime = System.currentTimeMillis();
        long currentTick = getLastPersistedTick();
        
        if (currentTick <= 0) {
            return 0.0;
        }
        
        // Calculate TPS since tick 0, excluding pause time
        long timeDiff = currentTime - startTime;
        if (timeDiff > 0) {
            // If paused, calculate TPS only for active time
            if (paused.get()) {
                // Estimate active time by assuming we're not paused for the entire duration
                // This is a simplified approach - in a real implementation you'd track pause/resume times
                long estimatedActiveTime = timeDiff - (timeDiff / 10); // Assume 90% active time when paused
                if (estimatedActiveTime > 0) {
                    return (double) currentTick / (estimatedActiveTime / 1000.0);
                }
            }
            return (double) currentTick / (timeDiff / 1000.0);
        }
        
        return 0.0;
    }

    public String getStatus() {
        if (!running.get()) {
            return "stopped";
        }
        
        if (paused.get()) {
            String dbInfo = jdbcUrlOverride != null ? jdbcUrlOverride : 
                           (dbFilePath != null ? dbFilePath.toString() : "unknown");
            long currentTick = getLastPersistedTick();
            String status = autoPaused.get() ? "auto-paused" : "paused";
            return String.format("%s tick:%d %s", status, currentTick, dbInfo);
        }
        
        String dbInfo = jdbcUrlOverride != null ? jdbcUrlOverride : 
                       (dbFilePath != null ? dbFilePath.toString() : "unknown");
        long currentTick = getLastPersistedTick();
        double tps = calculateTPS();
        
        return String.format("started tick:%d %s TPS:%.2f", currentTick, dbInfo, tps);
    }
    
    private void resetTPS() {
        // lastStatusTime = 0; // Removed
        // lastStatusTick = 0; // Removed
        // lastTPS = 0.0; // Removed
    }

    private void setupDatabase() {
        try {
            if (jdbcUrlOverride != null && !jdbcUrlOverride.isBlank()) {
                jdbcUrlInUse = jdbcUrlOverride;
                dbFilePath = null;
                connection = DriverManager.getConnection(jdbcUrlInUse);
            } else {
                // Only create new database if we don't have one yet
                if (dbFilePath == null) {
                    Path runsDir = Paths.get(Config.RUNS_DIRECTORY);
                    Files.createDirectories(runsDir);
                    String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                    // NEUER DATEINAME
                    dbFilePath = runsDir.resolve("sim_run_" + ts + "_raw.sqlite");
                    jdbcUrlInUse = "jdbc:sqlite:" + dbFilePath.toAbsolutePath();
                    
                    // Add performance hints for SQLite
                    connection = DriverManager.getConnection(jdbcUrlInUse);
                    try (Statement st = connection.createStatement()) {
                        st.execute("PRAGMA journal_mode=WAL"); // Write-Ahead Logging for better concurrency
                        st.execute("PRAGMA synchronous=NORMAL"); // Faster writes
                        st.execute("PRAGMA cache_size=10000"); // Larger cache
                        st.execute("PRAGMA temp_store=MEMORY"); // Use memory for temp tables
                    }
                } else {
                    // Reuse existing database
                    jdbcUrlInUse = "jdbc:sqlite:" + dbFilePath.toAbsolutePath();
                    connection = DriverManager.getConnection(jdbcUrlInUse);
                }
            }
            try (Statement st = connection.createStatement()) {
                // NEUES SCHEMA
                st.execute("CREATE TABLE IF NOT EXISTS program_artifacts (program_id TEXT PRIMARY KEY, artifact_json TEXT)");
                st.execute("CREATE TABLE IF NOT EXISTS raw_ticks (tick_number INTEGER PRIMARY KEY, tick_data_json TEXT)");
                st.execute("CREATE TABLE IF NOT EXISTS simulation_metadata (key TEXT PRIMARY KEY, value TEXT)");
                try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO simulation_metadata (key, value) VALUES (?, ?)")) {
                    if (worldShape != null) {
                        ps.setString(1, "worldShape");
                        ps.setString(2, objectMapper.writeValueAsString(worldShape));
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
        try {
            // Finalize any remaining batch operations
            if (tickInsertStatement != null) {
                if (batchCount % batchSize != 0) { // Use batchSize here
                    tickInsertStatement.executeBatch();
                }
                tickInsertStatement.close();
                tickInsertStatement = null;
            }
            
            // Ensure all WAL changes are checkpointed to the main database
            if (connection != null && !connection.isClosed()) {
                try (Statement st = connection.createStatement()) {
                    // Checkpoint WAL to main database
                    st.execute("PRAGMA wal_checkpoint(FULL)");
                    log.debug("WAL checkpoint completed before closing database");
                }
                connection.close();
                connection = null;
            }
        } catch (Exception e) {
            log.warn("Error during database cleanup: {}", e.getMessage());
        }
    }

    @Override
    public void run() {
        try {
            while (running.get()) {
                if (paused.get()) {
                    // In pause mode: check periodically for new ticks
                    Thread.sleep(1000); // Check every second for new ticks
                    
                    // Check if there are new ticks to process
                    // BUT: only auto-resume if we're in auto-pause mode, not manual pause
                    if (queue.size() > 0 && autoPaused.get()) {
                        log.debug("New ticks detected in queue, waking up from auto-pause");
                        paused.set(false);
                        autoPaused.set(false);
                        
                        // Reopen database connection since it was closed during manual pause
                        if (connection == null || connection.isClosed()) {
                            log.debug("Reopening database connection after auto-pause");
                            setupDatabase();
                            batchCount = 0;
                        }
                        continue;
                    }
                    continue;
                }

                try {
                    // Process multiple messages in batch for better performance
                    List<IQueueMessage> batch = new ArrayList<>();
                    IQueueMessage msg = queue.poll(100, TimeUnit.MILLISECONDS); // Non-blocking poll
                    if (msg != null) {
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
                                        log.debug("Executed final batch of {} ticks before pause", batchCount % batchSize);
                                    } catch (Exception e) {
                                        log.warn("Error executing final batch before pause: {}", e.getMessage());
                                    }
                                }
                                
                                // Perform WAL checkpoint to ensure all changes are in the main database
                                if (connection != null && !connection.isClosed()) {
                                    try (Statement st = connection.createStatement()) {
                                        // Checkpoint WAL to main database
                                        st.execute("PRAGMA wal_checkpoint(FULL)");
                                        log.debug("WAL checkpoint completed before manual pause");
                                    } catch (Exception e) {
                                        log.warn("Error during WAL checkpoint before pause: {}", e.getMessage());
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
                                log.debug("Executed final batch of {} ticks before auto-pause, result: {}", remainingCount, java.util.Arrays.toString(result));
                                tickInsertStatement.clearBatch();
                                batchCount = 0; // Reset batch counter after execution
                            } catch (Exception e) {
                                log.warn("Error executing final batch before auto-pause: {}", e.getMessage());
                            }
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
                        if (queue.size() > 0 && !paused.get()) {
                            log.debug("New ticks available, resuming persistence service");
                            autoPaused.set(false);
                            paused.set(false);
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
        // Wir brauchen den worldShape für die Linearisierung
        int[] worldShape = this.worldShape;
        if (worldShape == null) {
            throw new IllegalStateException(
                "Cannot serialize ProgramArtifact: worldShape is required for coordinate linearization. " +
                "Ensure worldShape is provided when creating PersistenceService."
            );
        }
        
        try {
            LinearizedProgramArtifact linearized = pam.programArtifact().toLinearized(worldShape);
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
                setupDatabase();
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
                log.debug("Executed batch of {} ticks, result: {}", batchSize, java.util.Arrays.toString(result));
                tickInsertStatement.clearBatch();
            }
            
            lastPersistedTick = rts.tickNumber();
        } catch (Exception e) {
            log.warn("Failed to serialize raw tick {}: {}", rts.tickNumber(), e.getMessage());
            throw e;
        }
    }
}