package org.evochora.server.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.evochora.server.IControllable;
import org.evochora.server.contracts.IQueueMessage;
import org.evochora.server.contracts.ProgramArtifactMessage;
import org.evochora.server.contracts.PreparedTickState;
import org.evochora.server.queue.ITickMessageQueue;
import org.evochora.runtime.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Consumes messages from a queue and persists them to a per-run SQLite database.
 * <p>
 * This service runs in its own thread, decoupling the simulation engine from disk I/O.
 * In performance mode, it omits persisting expensive debug-only information like program artifacts.
 */
public final class PersistenceService implements IControllable, Runnable {

    private static final Logger log = LoggerFactory.getLogger(PersistenceService.class);

    private final ITickMessageQueue queue;
    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final boolean performanceMode;
    private final int[] worldShape;

    private Connection connection;
    private Path dbFilePath;
    private final String jdbcUrlOverride;
    private String jdbcUrlInUse;
    private volatile long lastPersistedTick = -1L;

    /**
     * Constructs a new PersistenceService.
     *
     * @param queue The message queue to consume from.
     * @param performanceMode If true, debug-only information will not be persisted.
     */
    public PersistenceService(ITickMessageQueue queue, boolean performanceMode) {
        this(queue, performanceMode, null, null);
    }

    /**
     * Constructs a new PersistenceService with a specific JDBC URL, useful for testing.
     *
     * @param queue The message queue to consume from.
     * @param performanceMode If true, debug-only information will not be persisted.
     * @param jdbcUrlOverride The JDBC URL to use for the database connection.
     */
    public PersistenceService(ITickMessageQueue queue, boolean performanceMode, String jdbcUrlOverride) {
        this(queue, performanceMode, jdbcUrlOverride, null);
    }

    public PersistenceService(ITickMessageQueue queue, boolean performanceMode, int[] worldShape) {
        this(queue, performanceMode, null, worldShape);
    }

    private PersistenceService(ITickMessageQueue queue, boolean performanceMode, String jdbcUrlOverride, int[] worldShape) {
        this.queue = queue;
        this.performanceMode = performanceMode;
        this.jdbcUrlOverride = jdbcUrlOverride;
        this.worldShape = worldShape != null ? java.util.Arrays.copyOf(worldShape, worldShape.length) : null;
        this.thread = new Thread(this, "PersistenceService");
        this.thread.setDaemon(true);
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            setupDatabase();
            thread.start();
        }
    }

    @Override
    public void pause() { paused.set(true); }

    @Override
    public void resume() { paused.set(false); }

    @Override
    public void shutdown() {
        running.set(false);
        thread.interrupt();
        closeQuietly();
    }

    @Override
    public boolean isRunning() { return running.get(); }

    @Override
    public boolean isPaused() { return paused.get(); }

    public Path getDbFilePath() { return dbFilePath; }
    public long getLastPersistedTick() { return lastPersistedTick; }
    public String getJdbcUrl() { return jdbcUrlInUse; }

    private void setupDatabase() {
        try {
            if (jdbcUrlOverride != null && !jdbcUrlOverride.isBlank()) {
                jdbcUrlInUse = jdbcUrlOverride;
                dbFilePath = null;
                connection = DriverManager.getConnection(jdbcUrlInUse);
            } else {
                Path runsDir = Paths.get(Config.RUNS_DIRECTORY);
                Files.createDirectories(runsDir);
                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                dbFilePath = runsDir.resolve("sim_run_" + ts + ".sqlite");
                jdbcUrlInUse = "jdbc:sqlite:" + dbFilePath.toAbsolutePath();
                connection = DriverManager.getConnection(jdbcUrlInUse);
            }
            try (Statement st = connection.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS programs (programId TEXT PRIMARY KEY, artifactJson TEXT)");
                st.execute("CREATE TABLE IF NOT EXISTS prepared_ticks (tick_number INTEGER PRIMARY KEY, tick_data_json TEXT)");
                st.execute("CREATE TABLE IF NOT EXISTS simulation_metadata (key TEXT PRIMARY KEY, value TEXT)");
                try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO simulation_metadata (key, value) VALUES (?, ?)")) {
                    if (worldShape != null) {
                        ps.setString(1, "worldShape");
                        ps.setString(2, objectMapper.writeValueAsString(worldShape));
                        ps.executeUpdate();
                    }
                    // Removed isaMap persistence; opcode names are included per cell in prepared tick payloads
                    ps.setString(1, "runMode");
                    ps.setString(2, performanceMode ? "performance" : "debug");
                    ps.executeUpdate();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to setup database", e);
        }
    }

    private void closeQuietly() {
        try { if (connection != null) connection.close(); } catch (Exception ignored) {}
    }

    @Override
    public void run() {
        log.info("PersistenceService started, writing to {}", dbFilePath != null ? dbFilePath : jdbcUrlInUse);
        try {
            while (running.get()) {
                if (paused.get()) { Thread.onSpinWait(); continue; }

                IQueueMessage msg = queue.take();
                if (msg instanceof ProgramArtifactMessage pam) {
                    handleProgramArtifact(pam);
                } else if (msg instanceof PreparedTickState pts) {
                    handlePreparedTick(pts);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("PersistenceService error", e);
        } finally {
            log.info("PersistenceService stopped");
        }
    }

    private void handleProgramArtifact(ProgramArtifactMessage pam) throws Exception {
        if (this.performanceMode) return;
        String json = objectMapper.writeValueAsString(pam.programArtifact());
        try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO programs(programId, artifactJson) VALUES (?, ?)")) {
            ps.setString(1, pam.programId());
            ps.setString(2, json);
            ps.executeUpdate();
        }
    }

    private void handlePreparedTick(PreparedTickState pts) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO prepared_ticks(tick_number, tick_data_json) VALUES (?, ?)")) {
            ps.setLong(1, pts.tickNumber());
            ps.setString(2, objectMapper.writeValueAsString(pts));
            ps.executeUpdate();
        }
        lastPersistedTick = pts.tickNumber();
    }
}