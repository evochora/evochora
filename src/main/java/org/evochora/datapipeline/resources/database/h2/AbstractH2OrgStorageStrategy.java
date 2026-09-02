package org.evochora.datapipeline.resources.database.h2;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.database.TickNotFoundException;
import org.evochora.datapipeline.utils.H2SchemaUtil;
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
    
    /** Logger bound to the concrete strategy class, so log output carries the subclass name rather than this base class. */
    protected final Logger log = LoggerFactory.getLogger(getClass());
    /** Strategy configuration handed in at construction; never null, may be empty. */
    protected final Config options;
    /** Compression subclasses apply to the BLOB columns they write and read; derived from the strategy configuration. */
    protected final ICompressionCodec codec;
    private volatile boolean tablesCreated;
    
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

    /**
     * Marks tables as created, enabling streaming writes.
     * <p>
     * Subclasses MUST call this at the end of their {@link #createTables(Connection)} implementation.
     * Without this, {@link #ensureStreamingSession(Connection)} will throw {@link IllegalStateException}.
     */
    protected void markTablesCreated() {
        this.tablesCreated = true;
    }

    // ========================================================================
    // Per-connection streaming session state (thread-safe for competing consumers)
    // ========================================================================

    /**
     * Per-connection streaming session holding PreparedStatements and deduplication state.
     * <p>
     * Each competing consumer uses its own database connection, so keying by connection
     * ensures complete isolation between concurrent indexer instances.
     *
     * @param organismsStmt prepared MERGE for the static organism data, reused for every tick written on this connection
     * @param statesStmt prepared MERGE for the per-tick organism state
     * @param tickStatsStmt prepared MERGE for the per-tick statistics row
     * @param seenOrganisms organism ids already batched through {@code organismsStmt} in the current commit window, cleared on commit, so the static row is batched once per organism and commit
     */
    protected record StreamingSession(
            PreparedStatement organismsStmt,
            PreparedStatement statesStmt,
            PreparedStatement tickStatsStmt,
            Set<Integer> seenOrganisms
    ) {}

    /** SQL for the per-tick statistics shared by all organism storage strategies. */
    private static final String TICK_STATS_MERGE_SQL =
            "MERGE INTO organism_tick_stats (tick_number, total_organisms_created) "
            + "KEY (tick_number) VALUES (?, ?)";

    /** Per-connection sessions (thread-safe for competing consumers sharing this strategy instance). */
    private final ConcurrentHashMap<Connection, StreamingSession> sessions = new ConcurrentHashMap<>();

    /**
     * Returns the SQL string used for the organisms (static metadata) MERGE statement
     * during streaming writes.
     * <p>
     * Called once per connection during lazy initialization of {@link StreamingSession}.
     *
     * @return SQL string for MERGE operation on organisms table
     */
    protected abstract String getStreamOrganismsMergeSql();

    /**
     * Returns the SQL string used for the per-tick state MERGE statement
     * during streaming writes.
     * <p>
     * Called once per connection during lazy initialization of {@link StreamingSession}.
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
        if (!tablesCreated) {
            throw new IllegalStateException("createTables() must be called before writing organism data");
        }
        try {
            StreamingSession session = sessions.computeIfAbsent(conn, c -> {
                try {
                    return new StreamingSession(
                            c.prepareStatement(getStreamOrganismsMergeSql()),
                            c.prepareStatement(getStreamStatesMergeSql()),
                            c.prepareStatement(TICK_STATS_MERGE_SQL),
                            new HashSet<>()
                    );
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
            // Purge stale entries from closed connections (rare: only after connection failure)
            if (sessions.size() > 1) {
                purgeClosedConnections(conn);
            }
            return session;
        } catch (RuntimeException e) {
            if (e.getCause() instanceof SQLException) {
                throw (SQLException) e.getCause();
            }
            throw e;
        }
    }

    /**
     * Adds static organism metadata to the batch, deduplicating by organism ID.
     * <p>
     * Organisms already seen within the current commit window are skipped.
     * Sets 8 parameters: organism_id, parent_id, birth_tick, program_id,
     * initial_position, genome_hash, generation, parent_genome_hash.
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
                stmt.setInt(7, org.getGeneration());
                // NULL rather than 0 for an organism without a parent: a parent that carried no
                // genome writes a 0 here, and the two must stay distinguishable
                if (org.hasParentGenomeHash()) {
                    stmt.setLong(8, org.getParentGenomeHash());
                } else {
                    stmt.setNull(8, java.sql.Types.BIGINT);
                }
                stmt.addBatch();
            }
        }
    }

    /**
     * Creates the per-tick statistics table shared by all organism storage strategies.
     * <p>
     * Holds the running organism total the simulation reports with every tick. Strategies call
     * this from their own {@link #createTables(Connection)}; a strategy that does not use the
     * shared static organism data does not call it and does not get the table.
     *
     * @param stmt Statement on a connection with the run schema already set
     * @throws SQLException if the DDL fails for a reason other than the object already existing
     */
    protected void createTickStatsTable(Statement stmt) throws SQLException {
        H2SchemaUtil.executeDdlIfNotExists(
            stmt,
            "CREATE TABLE IF NOT EXISTS organism_tick_stats (" +
            "  tick_number BIGINT PRIMARY KEY," +
            "  total_organisms_created BIGINT NOT NULL" +
            ")",
            "organism_tick_stats"
        );
    }

    /**
     * Creates the index that makes an ancestor walk over the static organism data affordable.
     * <p>
     * Resolving one genome's parent genome selects the lowest-id carrier of that genome. Without
     * this index every step of the walk is a full table scan; with it, a step is a seek. The index
     * is offered here rather than imposed: a strategy that lays out the static organism data
     * differently does not call this and provides its own answer.
     * <p>
     * On an already populated table the build is a one-off blocking operation. It is instant for a
     * new run, where the table is still empty when the strategy creates its schema.
     *
     * @param stmt Statement on a connection with the run schema already set
     * @throws SQLException if the DDL fails for a reason other than the object already existing
     */
    protected void createGenomeIndex(Statement stmt) throws SQLException {
        H2SchemaUtil.executeDdlIfNotExists(
            stmt,
            "CREATE INDEX IF NOT EXISTS idx_organisms_genome ON organisms (genome_hash, organism_id)",
            "idx_organisms_genome"
        );
    }

    /**
     * Adds the per-tick statistics of one tick to the batch.
     * <p>
     * Called once per tick, including ticks whose organism list is empty: an extinction tick is
     * the one tick where the number of organisms ever created is the only surviving information.
     *
     * @param session The streaming session for the current connection
     * @param tick Tick data carrying the tick number and the running organism total
     * @throws SQLException if parameter setting or addBatch fails
     */
    protected void addTickStatsBatch(StreamingSession session, TickData tick) throws SQLException {
        PreparedStatement stmt = session.tickStatsStmt();
        stmt.setLong(1, tick.getTickNumber());
        stmt.setLong(2, tick.getTotalOrganismsCreated());
        stmt.addBatch();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Executes organism metadata, state and per-tick statistics batches, then clears the
     * deduplication set. Statements remain open for reuse in the next commit window.
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
        session.tickStatsStmt().executeBatch();

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
            closeQuietly(session.tickStatsStmt());
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
                    closeQuietly(entry.getValue().tickStatsStmt());
                    return true;
                }
            } catch (SQLException e) {
                closeQuietly(entry.getValue().organismsStmt());
                closeQuietly(entry.getValue().statesStmt());
                closeQuietly(entry.getValue().tickStatsStmt());
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
                log.debug("{}: failed to close streaming statement: {}",
                    getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    @Override
    public int readTotalOrganismsCreated(Connection conn, long tickNumber)
            throws SQLException, TickNotFoundException {
        String sql = "SELECT total_organisms_created FROM organism_tick_stats WHERE tick_number = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, tickNumber);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new TickNotFoundException("No organism tick statistics for tick " + tickNumber);
                }
                return rs.getInt(1);
            }
        }
    }
}
