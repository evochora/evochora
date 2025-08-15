package org.evochora.server.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.evochora.server.IControllable;
import org.evochora.server.contracts.IQueueMessage;
import org.evochora.server.contracts.ProgramArtifactMessage;
import org.evochora.server.contracts.WorldStateMessage;
import org.evochora.server.queue.ITickMessageQueue;
import org.evochora.runtime.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Consumes messages and persists them to a per-run SQLite database.
 */
public final class PersistenceService implements IControllable, Runnable {

    private static final Logger log = LoggerFactory.getLogger(PersistenceService.class);

    private final ITickMessageQueue queue;
    private final Object unused = null;
    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Connection connection;
    private Path dbFilePath;
    private volatile long lastPersistedTick = -1L;

    public PersistenceService(ITickMessageQueue queue) {
        this.queue = queue;
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

    private void setupDatabase() {
        try {
            Path runsDir = Paths.get(Config.RUNS_DIRECTORY);
            Files.createDirectories(runsDir);
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            dbFilePath = runsDir.resolve("sim_run_" + ts + ".sqlite");
            String url = "jdbc:sqlite:" + dbFilePath.toAbsolutePath();
            connection = DriverManager.getConnection(url);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("CREATE TABLE IF NOT EXISTS programs (programId TEXT PRIMARY KEY, artifactJson TEXT)");
                st.execute("CREATE TABLE IF NOT EXISTS ticks (tickNumber INTEGER PRIMARY KEY, timestampMicroseconds INTEGER, organismCount INTEGER)");
                st.execute("CREATE TABLE IF NOT EXISTS organism_states (tickNumber INTEGER, organismId INTEGER, programId TEXT, parentId INTEGER NULL, birthTick INTEGER, energy INTEGER, positionJson TEXT, dpJson TEXT, dvJson TEXT, stateJson TEXT, PRIMARY KEY (tickNumber, organismId))");
                st.execute("CREATE TABLE IF NOT EXISTS cell_states (tickNumber INTEGER, positionJson TEXT, type INTEGER, value INTEGER, ownerId INTEGER, PRIMARY KEY (tickNumber, positionJson))");
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
        log.info("PersistenceService started, writing to {}", dbFilePath);
        try {
            while (running.get()) {
                if (paused.get()) { Thread.onSpinWait(); continue; }

                IQueueMessage msg = queue.take();
                if (msg instanceof ProgramArtifactMessage pam) {
                    handleProgramArtifact(pam);
                } else if (msg instanceof WorldStateMessage wsm) {
                    handleWorldState(wsm);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("PersistenceService error", e);
        } finally {
            closeQuietly();
            log.info("PersistenceService stopped");
        }
    }

    private void handleProgramArtifact(ProgramArtifactMessage pam) throws Exception {
        String json = objectMapper.writeValueAsString(pam.programArtifact());
        try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO programs(programId, artifactJson) VALUES (?, ?)")) {
            ps.setString(1, pam.programId());
            ps.setString(2, json);
            ps.executeUpdate();
        }
    }

    private void handleWorldState(WorldStateMessage wsm) throws Exception {
        connection.setAutoCommit(false);
        try (PreparedStatement psTick = connection.prepareStatement(
                "INSERT OR REPLACE INTO ticks(tickNumber, timestampMicroseconds, organismCount) VALUES (?, ?, ?)")) {
            psTick.setLong(1, wsm.tickNumber());
            psTick.setLong(2, wsm.timestampMicroseconds());
            psTick.setInt(3, wsm.organismStates().size());
            psTick.executeUpdate();
        }

        try (PreparedStatement psOrg = connection.prepareStatement(
                "INSERT OR REPLACE INTO organism_states(tickNumber, organismId, programId, parentId, birthTick, energy, positionJson, dpJson, dvJson, stateJson) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (var org : wsm.organismStates()) {
                psOrg.setLong(1, wsm.tickNumber());
                psOrg.setInt(2, org.organismId());
                psOrg.setString(3, org.programId());
                if (org.parentId() == null) psOrg.setNull(4, Types.INTEGER); else psOrg.setInt(4, org.parentId());
                psOrg.setLong(5, org.birthTick());
                psOrg.setLong(6, org.energy());
                psOrg.setString(7, objectMapper.writeValueAsString(org.position()));
                psOrg.setString(8, objectMapper.writeValueAsString(org.dp()));
                psOrg.setString(9, objectMapper.writeValueAsString(org.dv()));
                var stateNode = objectMapper.createObjectNode();
                stateNode.put("ip", org.ip());
                stateNode.put("er", org.er());
                stateNode.putPOJO("dataRegisters", org.dataRegisters());
                stateNode.putPOJO("procRegisters", org.procRegisters());
                stateNode.putPOJO("dataStack", org.dataStack());
                stateNode.putPOJO("callStack", org.callStack());
                psOrg.setString(10, objectMapper.writeValueAsString(stateNode));
                psOrg.addBatch();
            }
            psOrg.executeBatch();
        }

        try (PreparedStatement psCell = connection.prepareStatement(
                "INSERT OR REPLACE INTO cell_states(tickNumber, positionJson, type, value, ownerId) VALUES (?, ?, ?, ?, ?)")) {
            for (var cell : wsm.cellStates()) {
                psCell.setLong(1, wsm.tickNumber());
                psCell.setString(2, objectMapper.writeValueAsString(cell.position()));
                psCell.setInt(3, cell.type());
                psCell.setInt(4, cell.value());
                psCell.setInt(5, cell.ownerId());
                psCell.addBatch();
            }
            psCell.executeBatch();
        }

        connection.commit();
        connection.setAutoCommit(true);
        lastPersistedTick = wsm.tickNumber();
    }
}


