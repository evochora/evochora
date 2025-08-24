package org.evochora.server.persistence;

import com.google.gson.Gson;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.server.contracts.IQueueMessage;
import org.evochora.server.contracts.raw.RawCellState;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.contracts.raw.RawTickState;
import org.evochora.server.queue.ITickMessageQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;

/**
 * Handles the persistence of raw simulation data to an SQLite database.
 * This service is optimized for high-speed, bulk writing of unprocessed data
 * from the simulation's hot path.
 */
public class PersistenceService implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(PersistenceService.class);
    private final ITickMessageQueue messageQueue;
    private final String dbUrl;
    private Connection connection;
    private final Gson gson = new Gson();

    public PersistenceService(final ITickMessageQueue messageQueue, final String databasePath) {
        this.messageQueue = messageQueue;
        this.dbUrl = "jdbc:sqlite:" + databasePath;
        initDatabase();
    }

    private void initDatabase() {
        try {
            connection = DriverManager.getConnection(dbUrl);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL;");
                stmt.execute("CREATE TABLE IF NOT EXISTS program_artifacts (program_id TEXT PRIMARY KEY, artifact_json TEXT);");
                stmt.execute("CREATE TABLE IF NOT EXISTS raw_organism_states (tick INTEGER, id INTEGER, state_json TEXT, PRIMARY KEY(tick, id));");
                stmt.execute("CREATE TABLE IF NOT EXISTS raw_cell_states (tick INTEGER, pos TEXT, molecule INTEGER, owner_id INTEGER, PRIMARY KEY(tick, pos));");
                logger.info("Raw database initialized successfully at {}.", dbUrl);
            }
        } catch (SQLException e) {
            logger.error("Failed to initialize the raw database.", e);
            throw new RuntimeException(e);
        }
    }

    public void persistProgramArtifacts(Collection<ProgramArtifact> artifacts) {
        final String sql = "INSERT OR REPLACE INTO program_artifacts (program_id, artifact_json) VALUES (?, ?);";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            for (ProgramArtifact artifact : artifacts) {
                // CORRECTED: ProgramArtifact is a record, so the accessor is programId()
                pstmt.setString(1, artifact.programId());
                pstmt.setString(2, gson.toJson(artifact));
                pstmt.addBatch();
            }
            pstmt.executeBatch();
            connection.commit();
            logger.info("Persisted {} program artifacts.", artifacts.size());
        } catch (SQLException e) {
            logger.error("Failed to persist program artifacts.", e);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                logger.error("Failed to reset auto-commit.", e);
            }
        }
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                final IQueueMessage message = messageQueue.take();
                if (message instanceof RawTickState tickState) {
                    handleRawTickState(tickState);
                }
            }
        } catch (InterruptedException e) {
            logger.info("Persistence service was interrupted. Shutting down.");
            Thread.currentThread().interrupt();
        } finally {
            closeConnection();
        }
    }

    private void handleRawTickState(final RawTickState tickState) {
        try {
            connection.setAutoCommit(false);
            persistOrganisms(tickState.tickNumber(), tickState.organisms());
            persistCells(tickState.tickNumber(), tickState.cells());
            connection.commit();
        } catch (SQLException e) {
            logger.error("Failed to persist tick {}.", tickState.tickNumber(), e);
            try {
                connection.rollback();
            } catch (SQLException ex) {
                logger.error("Failed to rollback transaction.", ex);
            }
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                logger.error("Failed to reset auto-commit.", e);
            }
        }
    }

    private void persistOrganisms(long tick, List<RawOrganismState> organisms) throws SQLException {
        final String sql = "INSERT OR REPLACE INTO raw_organism_states (tick, id, state_json) VALUES (?, ?, ?);";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            for (RawOrganismState organism : organisms) {
                pstmt.setLong(1, tick);
                pstmt.setInt(2, organism.id());
                pstmt.setString(3, gson.toJson(organism));
                pstmt.addBatch();
            }
            pstmt.executeBatch();
        }
    }

    private void persistCells(long tick, List<RawCellState> cells) throws SQLException {
        final String sql = "INSERT OR REPLACE INTO raw_cell_states (tick, pos, molecule, owner_id) VALUES (?, ?, ?, ?);";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            for (RawCellState cell : cells) {
                pstmt.setLong(1, tick);
                pstmt.setString(2, cell.pos()[0] + "|" + cell.pos()[1]);
                pstmt.setInt(3, cell.molecule());
                pstmt.setInt(4, cell.ownerId());
                pstmt.addBatch();
            }
            pstmt.executeBatch();
        }
    }

    private void closeConnection() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                logger.info("Database connection closed.");
            }
        } catch (SQLException e) {
            logger.error("Failed to close database connection.", e);
        }
    }
}
