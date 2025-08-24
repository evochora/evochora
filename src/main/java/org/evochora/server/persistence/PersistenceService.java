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
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final boolean performanceMode;
    private final int[] worldShape;

    private Connection connection;
    private Path dbFilePath;
    private final String jdbcUrlOverride;
    private String jdbcUrlInUse;
    private volatile long lastPersistedTick = -1L;

    public PersistenceService(ITickMessageQueue queue, boolean performanceMode) {
        this(queue, performanceMode, null, null);
    }

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
                // NEUER DATEINAME
                dbFilePath = runsDir.resolve("sim_run_" + ts + "_raw.sqlite");
                jdbcUrlInUse = "jdbc:sqlite:" + dbFilePath.toAbsolutePath();
                connection = DriverManager.getConnection(jdbcUrlInUse);
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
                } else if (msg instanceof RawTickState rts) { // GEÄNDERT
                    handleRawTick(rts); // GEÄNDERT
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
        // Im Performance-Modus werden keine Artefakte gespeichert.
        if (this.performanceMode) return;
        String json = objectMapper.writeValueAsString(pam.programArtifact());
        // GEÄNDERT: Tabellen- und Spaltennamen
        try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO program_artifacts(program_id, artifact_json) VALUES (?, ?)")) {
            ps.setString(1, pam.programId());
            ps.setString(2, json);
            ps.executeUpdate();
        }
    }

    // KOMPLETT NEU/UMBENANNT
    private void handleRawTick(RawTickState rts) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO raw_ticks(tick_number, tick_data_json) VALUES (?, ?)")) {
            ps.setLong(1, rts.tickNumber());
            // Serialisiere das gesamte RawTickState-Objekt als JSON
            ps.setString(2, objectMapper.writeValueAsString(rts));
            ps.executeUpdate();
        }
        lastPersistedTick = rts.tickNumber();
    }
}