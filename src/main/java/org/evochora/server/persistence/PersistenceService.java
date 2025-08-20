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
import org.evochora.runtime.isa.Instruction;

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
        this.queue = queue;
        this.performanceMode = performanceMode;
        this.jdbcUrlOverride = null;
        this.thread = new Thread(this, "PersistenceService");
        this.thread.setDaemon(true);
    }

    /**
     * Constructs a new PersistenceService with a specific JDBC URL, useful for testing.
     *
     * @param queue The message queue to consume from.
     * @param performanceMode If true, debug-only information will not be persisted.
     * @param jdbcUrlOverride The JDBC URL to use for the database connection.
     */
    public PersistenceService(ITickMessageQueue queue, boolean performanceMode, String jdbcUrlOverride) {
        this.queue = queue;
        this.performanceMode = performanceMode;
        this.jdbcUrlOverride = jdbcUrlOverride;
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
                st.execute("CREATE TABLE IF NOT EXISTS ticks (tickNumber INTEGER PRIMARY KEY, timestampMicroseconds INTEGER, organismCount INTEGER)");
                st.execute("CREATE TABLE IF NOT EXISTS organism_states (" +
                        "tickNumber INTEGER, organismId INTEGER, programId TEXT, parentId INTEGER NULL, " +
                        "birthTick INTEGER, energy INTEGER, positionJson TEXT, dpsJson TEXT, dvJson TEXT, " + // CHANGED: dpJson -> dpsJson
                        "stateJson TEXT, disassembledInstructionJson TEXT, " +
                        "dataRegisters TEXT, procRegisters TEXT, dataStack TEXT, callStack TEXT, formalParameters TEXT, fprs TEXT, " +
                        "locationRegisters TEXT, locationStack TEXT, " + // NEW COLUMNS
                        "PRIMARY KEY (tickNumber, organismId))");
                // Idempotent schema upgrades for backward compatibility
                try { st.execute("ALTER TABLE organism_states ADD COLUMN fprs TEXT"); } catch (SQLException ignore) {}
                try { st.execute("ALTER TABLE organism_states ADD COLUMN locationRegisters TEXT"); } catch (SQLException ignore) {}
                try { st.execute("ALTER TABLE organism_states ADD COLUMN locationStack TEXT"); } catch (SQLException ignore) {}
                // Rename dpJson to dpsJson if the old column exists
                try { st.execute("ALTER TABLE organism_states RENAME COLUMN dpJson TO dpsJson"); } catch (SQLException ignore) {}


                st.execute("CREATE TABLE IF NOT EXISTS cell_states (tickNumber INTEGER, positionJson TEXT, type INTEGER, value INTEGER, ownerId INTEGER, PRIMARY KEY (tickNumber, positionJson))");

                st.execute("CREATE TABLE IF NOT EXISTS simulation_metadata (key TEXT PRIMARY KEY, value TEXT)");
                try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO simulation_metadata (key, value) VALUES (?, ?)")) {
                    ps.setString(1, "worldShape");
                    ps.setString(2, objectMapper.writeValueAsString(Config.WORLD_SHAPE));
                    ps.executeUpdate();
                    ps.setString(1, "isaMap");
                    ps.setString(2, objectMapper.writeValueAsString(Instruction.getIdToNameMap()));
                    ps.executeUpdate();
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
                } else if (msg instanceof WorldStateMessage wsm) {
                    handleWorldState(wsm);
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

    private void handleWorldState(WorldStateMessage wsm) throws Exception {
        connection.setAutoCommit(false);
        try (PreparedStatement psTick = connection.prepareStatement(
                "INSERT OR REPLACE INTO ticks(tickNumber, timestampMicroseconds, organismCount) VALUES (?, ?, ?)")) {
            psTick.setLong(1, wsm.tickNumber());
            psTick.setLong(2, wsm.timestampMicroseconds());
            psTick.setInt(3, wsm.organismStates().size());
            psTick.executeUpdate();
        }

        final String orgSql = "INSERT OR REPLACE INTO organism_states(" +
                "tickNumber, organismId, programId, parentId, birthTick, energy, positionJson, dpsJson, dvJson, " +
                "stateJson, disassembledInstructionJson, dataRegisters, procRegisters, dataStack, callStack, formalParameters, fprs, " +
                "locationRegisters, locationStack) " + // NEW COLUMNS
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"; // UPDATED placeholder count

        try (PreparedStatement psOrg = connection.prepareStatement(orgSql)) {
            for (var org : wsm.organismStates()) {
                psOrg.setLong(1, wsm.tickNumber());
                psOrg.setInt(2, org.organismId());
                psOrg.setString(3, org.programId());
                if (org.parentId() == null) psOrg.setNull(4, Types.INTEGER); else psOrg.setInt(4, org.parentId());
                psOrg.setLong(5, org.birthTick());
                psOrg.setLong(6, org.energy());
                psOrg.setString(7, objectMapper.writeValueAsString(org.position()));
                psOrg.setString(8, objectMapper.writeValueAsString(org.dps())); // CHANGED: from dp() to dps()
                psOrg.setString(9, objectMapper.writeValueAsString(org.dv()));

                java.util.Map<String, Object> stateMap = new java.util.LinkedHashMap<>();
                stateMap.put("ip", org.ip());
                stateMap.put("er", org.er());
                psOrg.setString(10, objectMapper.writeValueAsString(stateMap));

                psOrg.setString(11, org.disassembledInstructionJson());
                psOrg.setString(12, objectMapper.writeValueAsString(org.dataRegisters()));
                psOrg.setString(13, objectMapper.writeValueAsString(org.procRegisters()));
                psOrg.setString(14, objectMapper.writeValueAsString(org.dataStack()));
                psOrg.setString(15, objectMapper.writeValueAsString(org.callStack()));
                psOrg.setString(16, objectMapper.writeValueAsString(org.formalParameters()));
                psOrg.setString(17, objectMapper.writeValueAsString(org.fprs()));

                // NEW: Persist location registers and stack
                psOrg.setString(18, objectMapper.writeValueAsString(org.locationRegisters()));
                psOrg.setString(19, objectMapper.writeValueAsString(org.locationStack()));

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