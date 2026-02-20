package org.evochora.datapipeline.resources.database.h2;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

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
    // Streaming session state (instance fields — competing-consumer safe)
    // ========================================================================

    /** Cached PreparedStatement for static organism metadata MERGE. */
    protected PreparedStatement streamOrganismsStmt;

    /** Cached PreparedStatement for per-tick organism state writes. */
    protected PreparedStatement streamStatesStmt;

    /** Tracks organism IDs already batched within the current commit window. */
    protected Set<Integer> streamSeenOrganisms;

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
     * Initializes streaming session state (PreparedStatements, deduplication set)
     * on first call. Subsequent calls are no-ops.
     *
     * @param conn Database connection (autoCommit=false)
     * @throws SQLException if statement preparation fails
     */
    protected void ensureStreamingSession(Connection conn) throws SQLException {
        if (streamOrganismsStmt == null) {
            streamOrganismsStmt = conn.prepareStatement(getStreamOrganismsMergeSql());
            streamStatesStmt = conn.prepareStatement(getStreamStatesMergeSql());
            streamSeenOrganisms = new HashSet<>();
        }
    }

    /**
     * Adds static organism metadata to the batch, deduplicating by organism ID.
     * <p>
     * Organisms already seen within the current commit window are skipped.
     * Sets 6 parameters: organism_id, parent_id, birth_tick, program_id,
     * initial_position, genome_hash.
     *
     * @param tick Tick data containing organism states
     * @throws SQLException if parameter setting or addBatch fails
     */
    protected void addOrganismMetadataBatch(TickData tick) throws SQLException {
        for (OrganismState org : tick.getOrganismsList()) {
            int organismId = org.getOrganismId();
            if (streamSeenOrganisms.add(organismId)) {
                streamOrganismsStmt.setInt(1, organismId);
                if (org.hasParentId()) {
                    streamOrganismsStmt.setInt(2, org.getParentId());
                } else {
                    streamOrganismsStmt.setNull(2, java.sql.Types.INTEGER);
                }
                streamOrganismsStmt.setLong(3, org.getBirthTick());
                streamOrganismsStmt.setString(4, org.getProgramId());
                streamOrganismsStmt.setBytes(5, org.getInitialPosition().toByteArray());
                streamOrganismsStmt.setLong(6, org.getGenomeHash());
                streamOrganismsStmt.addBatch();
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
        if (streamOrganismsStmt == null) {
            return; // No data was added
        }

        if (!streamSeenOrganisms.isEmpty()) {
            streamOrganismsStmt.executeBatch();
        }
        streamStatesStmt.executeBatch();

        // Reset per-commit state; statements stay open for reuse
        streamSeenOrganisms.clear();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Closes open PreparedStatements (suppressing errors) and clears
     * accumulated deduplication state. The next {@link #addOrganismTick}
     * call will lazily re-initialize all session resources.
     */
    @Override
    public void resetStreamingState() {
        closeQuietly(streamOrganismsStmt);
        closeQuietly(streamStatesStmt);
        streamOrganismsStmt = null;
        streamStatesStmt = null;
        streamSeenOrganisms = null;
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
