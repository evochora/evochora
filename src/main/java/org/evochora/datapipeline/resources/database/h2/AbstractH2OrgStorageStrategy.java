package org.evochora.datapipeline.resources.database.h2;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.utils.compression.CompressionCodecFactory;
import org.evochora.datapipeline.utils.compression.ICompressionCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

/**
 * Abstract base class for H2 organism storage strategies.
 * <p>
 * Enforces constructor contract: All strategies MUST accept Config parameter.
 * <p>
 * Provides common infrastructure:
 * <ul>
 *   <li>Config options access (protected final)</li>
 *   <li>Logger instance (protected final)</li>
 *   <li>Compression codec (protected final)</li>
 * </ul>
 * <p>
 * <strong>Rationale:</strong> Ensures all strategies can be instantiated via reflection
 * with consistent constructor signature. The compiler enforces that subclasses call
 * super(options), preventing runtime errors from missing constructors.
 */
public abstract class AbstractH2OrgStorageStrategy implements IH2OrgStorageStrategy {
    
    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final Config options;
    protected final ICompressionCodec codec;
    
    /**
     * Creates storage strategy with configuration.
     * <p>
     * <strong>Subclass Requirement:</strong> All subclasses MUST call super(options).
     * The compiler enforces this.
     * 
     * @param options Strategy configuration (may be empty, never null)
     */
    protected AbstractH2OrgStorageStrategy(Config options) {
        this.options = Objects.requireNonNull(options, "options cannot be null");
        this.codec = CompressionCodecFactory.create(options);
        log.debug("{} initialized with compression: {}", getClass().getSimpleName(), codec.getName());
    }
    
    /**
     * Returns the configured compression codec.
     * <p>
     * Subclasses use this for BLOB compression/decompression.
     *
     * @return The compression codec (never null)
     */
    protected ICompressionCodec getCodec() {
        return codec;
    }

    // ========================================================================
    // Per-connection streaming session state (thread-safe for competing consumers)
    // ========================================================================

    /**
     * Per-connection streaming session holding PreparedStatements and deduplication state.
     * <p>
     * Each competing consumer uses its own database connection, so keying by connection
     * ensures complete isolation between concurrent indexer instances.
     */
    protected record StreamingSession(
            PreparedStatement organismsStmt,
            PreparedStatement statesStmt,
            Set<Integer> seenOrganisms
    ) {}

    /** Per-connection sessions (thread-safe for competing consumers sharing this strategy instance). */
    private final ConcurrentHashMap<Connection, StreamingSession> sessions = new ConcurrentHashMap<>();

    /**
     * Returns the SQL string used for the organisms (static metadata) MERGE statement
     * during streaming writes.
     * <p>
     * Called once during lazy initialization of {@link #streamOrganismsStmt}.
     *
     * @return SQL string for MERGE operation on organisms table
     */
    protected abstract String getStreamOrganismsMergeSql();

    /**
     * Returns the SQL string used for the per-tick state MERGE statement
     * during streaming writes.
     * <p>
     * Called once during lazy initialization of {@link #streamStatesStmt}.
     *
     * @return SQL string for MERGE operation on organism states table
     */
    protected abstract String getStreamStatesMergeSql();

    /**
     * Returns the streaming session for the given connection, creating it lazily.
     * <p>
     * Each connection gets its own PreparedStatements and deduplication set,
     * ensuring competing consumers sharing this strategy instance are fully isolated.
     *
     * @param conn Database connection (autoCommit=false)
     * @return The streaming session for this connection
     * @throws SQLException if statement preparation fails
     */
    protected StreamingSession ensureStreamingSession(Connection conn) throws SQLException {
        StreamingSession session = sessions.get(conn);
        if (session == null) {
            try {
                session = new StreamingSession(
                        conn.prepareStatement(getStreamOrganismsMergeSql()),
                        conn.prepareStatement(getStreamStatesMergeSql()),
                        new HashSet<>()
                );
                sessions.put(conn, session);
            } catch (SQLException e) {
                throw e;
            }
        }
        // Purge stale entries from closed connections (rare: only after connection failure)
        if (sessions.size() > 1) {
            purgeClosedConnections(conn);
        }
        return session;
    }

    /**
     * Adds static organism metadata to the batch, deduplicating by organism ID.
     * <p>
     * Organisms already seen within the current commit window are skipped.
     * Sets 6 parameters: organism_id, parent_id, birth_tick, program_id,
     * initial_position, genome_hash.
     *
     * @param session The streaming session for the current connection
     * @param tick Tick data containing organism states
     * @throws SQLException if parameter setting or addBatch fails
     */
    protected void addOrganismMetadataBatch(StreamingSession session, TickData tick) throws SQLException {
        PreparedStatement stmt = session.organismsStmt();
        Set<Integer> seen = session.seenOrganisms();
        for (OrganismState org : tick.getOrganismsList()) {
            int organismId = org.getOrganismId();
            if (seen.add(organismId)) {
                stmt.setInt(1, organismId);
                if (org.hasParentId()) {
                    stmt.setInt(2, org.getParentId());
                } else {
                    stmt.setNull(2, java.sql.Types.INTEGER);
                }
                stmt.setLong(3, org.getBirthTick());
                stmt.setString(4, org.getProgramId());
                stmt.setBytes(5, org.getInitialPosition().toByteArray());
                stmt.setLong(6, org.getGenomeHash());
                stmt.addBatch();
            }
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Executes organism metadata and state batches, then clears the deduplication set.
     * Statements remain open for reuse in the next commit window.
     */
    @Override
    public void commitOrganismWrites(Connection conn) throws SQLException {
        StreamingSession session = sessions.get(conn);
        if (session == null) {
            return; // No data was added for this connection
        }

        if (!session.seenOrganisms().isEmpty()) {
            session.organismsStmt().executeBatch();
        }
        session.statesStmt().executeBatch();

        // Reset per-commit state; statements stay open for reuse
        session.seenOrganisms().clear();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Closes open PreparedStatements (suppressing errors) and removes the session
     * for the given connection. The next {@link #addOrganismTick} call will lazily
     * re-initialize all session resources.
     */
    @Override
    public void resetStreamingState(Connection conn) {
        StreamingSession session = sessions.remove(conn);
        if (session != null) {
            closeQuietly(session.organismsStmt());
            closeQuietly(session.statesStmt());
        }
    }

    /**
     * Removes entries for closed connections from {@link #sessions}.
     * <p>
     * Called opportunistically when the map has more than one entry, which only
     * happens after a connection failure caused the wrapper to acquire a new connection.
     *
     * @param currentConn the active connection (not purged)
     */
    private void purgeClosedConnections(Connection currentConn) {
        sessions.entrySet().removeIf(entry -> {
            Connection c = entry.getKey();
            if (c == currentConn) {
                return false;
            }
            try {
                if (c.isClosed()) {
                    closeQuietly(entry.getValue().organismsStmt());
                    closeQuietly(entry.getValue().statesStmt());
                    return true;
                }
            } catch (SQLException e) {
                return true;
            }
            return false;
        });
    }

    /**
     * Closes a PreparedStatement, suppressing any exceptions.
     *
     * @param stmt Statement to close (may be null)
     */
    private void closeQuietly(PreparedStatement stmt) {
        if (stmt != null) {
            try {
                stmt.close();
            } catch (SQLException e) {
                log.debug("Failed to close streaming statement: {}", e.getMessage());
            }
        }
    }

    @Override
    public int readTotalOrganismsCreated(Connection conn, long tickNumber) throws SQLException {
        String sql = "SELECT MAX(organism_id) FROM organisms WHERE birth_tick <= ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, tickNumber);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
