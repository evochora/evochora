package org.evochora.datapipeline.resources.database.h2;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.evochora.datapipeline.api.contracts.DataPointerList;
import org.evochora.datapipeline.api.contracts.OrganismRuntimeState;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickSummary;
import org.evochora.datapipeline.api.resources.database.dto.TickRange;
import org.evochora.datapipeline.resources.database.OrganismStateConverter;
import org.evochora.datapipeline.utils.H2SchemaUtil;
import org.evochora.datapipeline.utils.compression.CompressionCodecFactory;
import org.evochora.datapipeline.utils.compression.ICompressionCodec;

import com.typesafe.config.Config;

/**
 * Row-per-organism storage strategy for H2 database.
 * <p>
 * This strategy stores one row per organism per tick in the {@code organism_states} table.
 * It provides <strong>backward compatibility</strong> with simulation runs created before
 * the BLOB-based {@link SingleBlobOrgStrategy} was introduced.
 * <p>
 * <strong>Table Schema:</strong>
 * <pre>
 * CREATE TABLE organism_states (
 *   tick_number        BIGINT NOT NULL,
 *   organism_id        INT    NOT NULL,
 *   energy             INT    NOT NULL,
 *   ip                 BYTEA  NOT NULL,     -- Vector (Protobuf)
 *   dv                 BYTEA  NOT NULL,     -- Vector (Protobuf)
 *   data_pointers      BYTEA  NOT NULL,     -- DataPointerList (Protobuf)
 *   active_dp_index    INT    NOT NULL,
 *   runtime_state_blob BYTEA  NOT NULL,     -- OrganismRuntimeState (compressed)
 *   PRIMARY KEY (tick_number, organism_id)
 * );
 * </pre>
 * <p>
 * <strong>Trade-offs vs SingleBlobOrgStrategy:</strong>
 * <ul>
 *   <li>Slower writes (one MERGE per organism vs one per tick)</li>
 *   <li>More rows (100 organisms × 200k ticks = 20M rows vs 200k rows)</li>
 *   <li>Better for single-organism queries (direct row lookup vs BLOB scan)</li>
 *   <li>Required for reading old simulation data</li>
 * </ul>
 * <p>
 * <strong>Configuration:</strong>
 * <pre>
 * h2OrganismStrategy {
 *   className = "org.evochora.datapipeline.resources.database.h2.RowPerOrganismStrategy"
 *   options {
 *     compression {
 *       enabled = true
 *       codec = "zstd"
 *       level = 3
 *     }
 *   }
 * }
 * </pre>
 */
public class RowPerOrganismStrategy extends AbstractH2OrgStorageStrategy {

    // Cached SQL strings (immutable after construction)
    private static final String ORGANISMS_MERGE_SQL =
            "MERGE INTO organisms (" +
                    "organism_id, parent_id, birth_tick, program_id, initial_position" +
                    ") KEY (organism_id) VALUES (?, ?, ?, ?, ?)";

    private static final String STATES_MERGE_SQL =
            "MERGE INTO organism_states (" +
                    "tick_number, organism_id, energy, ip, dv, data_pointers, active_dp_index, runtime_state_blob" +
                    ") KEY (tick_number, organism_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    /**
     * Creates a new RowPerOrganismStrategy with the given configuration.
     *
     * @param options Configuration options (compression settings)
     */
    public RowPerOrganismStrategy(Config options) {
        super(options);
    }

    @Override
    public void createTables(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // Static organism metadata table (same as SingleBlobOrgStrategy)
            H2SchemaUtil.executeDdlIfNotExists(
                    stmt,
                    "CREATE TABLE IF NOT EXISTS organisms (" +
                            "  organism_id INT PRIMARY KEY," +
                            "  parent_id INT NULL," +
                            "  birth_tick BIGINT NOT NULL," +
                            "  program_id TEXT NOT NULL," +
                            "  initial_position BYTEA NOT NULL" +
                            ")",
                    "organisms"
            );

            // Per-tick organism state table (row-per-organism)
            H2SchemaUtil.executeDdlIfNotExists(
                    stmt,
                    "CREATE TABLE IF NOT EXISTS organism_states (" +
                            "  tick_number BIGINT NOT NULL," +
                            "  organism_id INT NOT NULL," +
                            "  energy INT NOT NULL," +
                            "  ip BYTEA NOT NULL," +
                            "  dv BYTEA NOT NULL," +
                            "  data_pointers BYTEA NOT NULL," +
                            "  active_dp_index INT NOT NULL," +
                            "  runtime_state_blob BYTEA NOT NULL," +
                            "  PRIMARY KEY (tick_number, organism_id)" +
                            ")",
                    "organism_states"
            );

            // Optional helper index for per-organism history queries
            H2SchemaUtil.executeDdlIfNotExists(
                    stmt,
                    "CREATE INDEX IF NOT EXISTS idx_organism_states_org ON organism_states (organism_id)",
                    "idx_organism_states_org"
            );
        }

        conn.commit();
    }

    @Override
    public String getOrganismsMergeSql() {
        return ORGANISMS_MERGE_SQL;
    }

    @Override
    public String getStatesMergeSql() {
        return STATES_MERGE_SQL;
    }

    @Override
    public void writeOrganisms(Connection conn, PreparedStatement stmt, List<TickData> ticks)
            throws SQLException {
        // Extract unique organisms from all ticks and write to static table
        java.util.Set<Integer> seenOrganisms = new java.util.HashSet<>();

        for (TickData tick : ticks) {
            for (OrganismState org : tick.getOrganismsList()) {
                int organismId = org.getOrganismId();
                if (seenOrganisms.add(organismId)) {
                    stmt.setInt(1, organismId);
                    if (org.hasParentId()) {
                        stmt.setInt(2, org.getParentId());
                    } else {
                        stmt.setNull(2, java.sql.Types.INTEGER);
                    }
                    stmt.setLong(3, org.getBirthTick());
                    stmt.setString(4, org.getProgramId());
                    stmt.setBytes(5, org.getInitialPosition().toByteArray());
                    stmt.addBatch();
                }
            }
        }

        if (!seenOrganisms.isEmpty()) {
            stmt.executeBatch();
            log.debug("Wrote {} unique organisms to organisms table", seenOrganisms.size());
        }
    }

    @Override
    public void writeStates(Connection conn, PreparedStatement stmt, List<TickData> ticks)
            throws SQLException {
        if (ticks.isEmpty()) {
            return;
        }

        ICompressionCodec codec = getCodec();
        int writtenCount = 0;

        for (TickData tick : ticks) {
            long tickNumber = tick.getTickNumber();

            for (OrganismState org : tick.getOrganismsList()) {
                // Build runtime_state_blob from OrganismState fields
                byte[] runtimeBlob = buildRuntimeStateBlob(org, codec);

                // Build data_pointers blob
                DataPointerList.Builder dpBuilder = DataPointerList.newBuilder();
                for (Vector dp : org.getDataPointersList()) {
                    dpBuilder.addDataPointers(dp);
                }
                byte[] dataPointersBytes = dpBuilder.build().toByteArray();

                stmt.setLong(1, tickNumber);
                stmt.setInt(2, org.getOrganismId());
                stmt.setInt(3, org.getEnergy());
                stmt.setBytes(4, org.getIp().toByteArray());
                stmt.setBytes(5, org.getDv().toByteArray());
                stmt.setBytes(6, dataPointersBytes);
                stmt.setInt(7, org.getActiveDpIndex());
                stmt.setBytes(8, runtimeBlob);
                stmt.addBatch();
                writtenCount++;
            }
        }

        if (writtenCount > 0) {
            stmt.executeBatch();
            log.debug("Wrote {} organism states to organism_states table (row-per-organism strategy)",
                    writtenCount);
        }
    }

    /**
     * Builds the compressed runtime_state_blob from an OrganismState.
     * <p>
     * The blob contains an OrganismRuntimeState Protobuf message with:
     * registers, stacks, call stacks, instruction execution data.
     */
    private byte[] buildRuntimeStateBlob(OrganismState org, ICompressionCodec codec) {
        OrganismRuntimeState.Builder runtimeStateBuilder = OrganismRuntimeState.newBuilder()
                .addAllDataRegisters(org.getDataRegistersList())
                .addAllProcedureRegisters(org.getProcedureRegistersList())
                .addAllFormalParamRegisters(org.getFormalParamRegistersList())
                .addAllLocationRegisters(org.getLocationRegistersList())
                .addAllDataStack(org.getDataStackList())
                .addAllLocationStack(org.getLocationStackList())
                .addAllCallStack(org.getCallStackList())
                .setInstructionFailed(org.getInstructionFailed())
                .setFailureReason(org.hasFailureReason() ? org.getFailureReason() : "")
                .addAllFailureCallStack(org.getFailureCallStackList());

        // Instruction execution data
        if (org.hasInstructionOpcodeId()) {
            runtimeStateBuilder.setInstructionOpcodeId(org.getInstructionOpcodeId());
        }
        if (org.getInstructionRawArgumentsCount() > 0) {
            runtimeStateBuilder.addAllInstructionRawArguments(org.getInstructionRawArgumentsList());
        }
        if (org.hasInstructionEnergyCost()) {
            runtimeStateBuilder.setInstructionEnergyCost(org.getInstructionEnergyCost());
        }
        if (org.hasIpBeforeFetch()) {
            runtimeStateBuilder.setInstructionIpBeforeFetch(org.getIpBeforeFetch());
        }
        if (org.hasDvBeforeFetch()) {
            runtimeStateBuilder.setInstructionDvBeforeFetch(org.getDvBeforeFetch());
        }
        if (org.getInstructionRegisterValuesBeforeCount() > 0) {
            runtimeStateBuilder.putAllInstructionRegisterValuesBefore(org.getInstructionRegisterValuesBeforeMap());
        }

        OrganismRuntimeState runtimeState = runtimeStateBuilder.build();

        // Compress with configured codec
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (OutputStream out = codec.wrapOutputStream(baos)) {
                runtimeState.writeTo(out);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to compress runtime_state_blob", e);
        }
    }

    @Override
    public List<OrganismTickSummary> readOrganismsAtTick(Connection conn, long tickNumber)
            throws SQLException {
        String sql = """
                SELECT s.organism_id, s.energy, s.ip, s.dv, s.data_pointers, s.active_dp_index,
                       o.parent_id, o.birth_tick
                FROM organism_states s
                LEFT JOIN organisms o ON s.organism_id = o.organism_id
                WHERE s.tick_number = ?
                ORDER BY s.organism_id
                """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, tickNumber);
            try (ResultSet rs = stmt.executeQuery()) {
                List<OrganismTickSummary> result = new ArrayList<>();
                while (rs.next()) {
                    int organismId = rs.getInt("organism_id");
                    int energy = rs.getInt("energy");
                    byte[] ipBytes = rs.getBytes("ip");
                    byte[] dvBytes = rs.getBytes("dv");
                    byte[] dpBytes = rs.getBytes("data_pointers");
                    int activeDpIndex = rs.getInt("active_dp_index");

                    // Static info from organisms table (may be null if JOIN fails)
                    int parentIdRaw = rs.getInt("parent_id");
                    Integer parentId = rs.wasNull() ? null : parentIdRaw;
                    long birthTick = rs.getLong("birth_tick");

                    int[] ip = OrganismStateConverter.decodeVector(ipBytes);
                    int[] dv = OrganismStateConverter.decodeVector(dvBytes);
                    int[][] dataPointers = OrganismStateConverter.decodeDataPointers(dpBytes);

                    // SR is stored in runtime_state_blob, not as a separate column.
                    // For backward compatibility, we return 0 (old data has no SR).
                    int entropyRegister = 0;

                    result.add(new OrganismTickSummary(
                            organismId,
                            energy,
                            ip,
                            dv,
                            dataPointers,
                            activeDpIndex,
                            parentId,
                            birthTick,
                            entropyRegister
                    ));
                }
                return result;
            }
        }
    }

    @Override
    public OrganismState readSingleOrganismState(Connection conn, long tickNumber, int organismId)
            throws SQLException {
        String sql = """
                SELECT energy, ip, dv, data_pointers, active_dp_index, runtime_state_blob
                FROM organism_states
                WHERE tick_number = ? AND organism_id = ?
                """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, tickNumber);
            stmt.setInt(2, organismId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null; // Not found
                }

                int energy = rs.getInt("energy");
                byte[] ipBytes = rs.getBytes("ip");
                byte[] dvBytes = rs.getBytes("dv");
                byte[] dpBytes = rs.getBytes("data_pointers");
                int activeDpIndex = rs.getInt("active_dp_index");
                byte[] blobBytes = rs.getBytes("runtime_state_blob");

                // Reconstruct OrganismState from row columns + runtime_state_blob
                return reconstructOrganismState(
                        organismId, energy, ipBytes, dvBytes, dpBytes, activeDpIndex, blobBytes);
            }
        }
    }

    /**
     * Reconstructs an OrganismState Protobuf from the row-per-organism table columns.
     * <p>
     * This merges the "hot path" columns (energy, ip, dv, data_pointers, active_dp_index)
     * with the runtime_state_blob (registers, stacks, instruction data).
     */
    private OrganismState reconstructOrganismState(
            int organismId,
            int energy,
            byte[] ipBytes,
            byte[] dvBytes,
            byte[] dpBytes,
            int activeDpIndex,
            byte[] blobBytes) throws SQLException {

        // Decode Vector fields
        Vector ip;
        Vector dv;
        try {
            ip = Vector.parseFrom(ipBytes);
            dv = Vector.parseFrom(dvBytes);
        } catch (Exception e) {
            throw new SQLException("Failed to decode ip/dv vectors", e);
        }

        // Decode DataPointerList
        List<Vector> dataPointers;
        try {
            DataPointerList dpList = DataPointerList.parseFrom(dpBytes);
            dataPointers = dpList.getDataPointersList();
        } catch (Exception e) {
            throw new SQLException("Failed to decode data_pointers", e);
        }

        // Decode and decompress runtime_state_blob
        OrganismRuntimeState runtimeState;
        try {
            ICompressionCodec codec = CompressionCodecFactory.detectFromMagicBytes(blobBytes);
            try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(blobBytes);
                 java.io.InputStream in = codec.wrapInputStream(bais)) {
                runtimeState = OrganismRuntimeState.parseFrom(in);
            }
        } catch (Exception e) {
            throw new SQLException("Failed to decode runtime_state_blob", e);
        }

        // Build complete OrganismState
        OrganismState.Builder builder = OrganismState.newBuilder()
                .setOrganismId(organismId)
                .setEnergy(energy)
                .setIp(ip)
                .setDv(dv)
                .addAllDataPointers(dataPointers)
                .setActiveDpIndex(activeDpIndex)
                // From runtime_state_blob
                .addAllDataRegisters(runtimeState.getDataRegistersList())
                .addAllProcedureRegisters(runtimeState.getProcedureRegistersList())
                .addAllFormalParamRegisters(runtimeState.getFormalParamRegistersList())
                .addAllLocationRegisters(runtimeState.getLocationRegistersList())
                .addAllDataStack(runtimeState.getDataStackList())
                .addAllLocationStack(runtimeState.getLocationStackList())
                .addAllCallStack(runtimeState.getCallStackList())
                .setInstructionFailed(runtimeState.getInstructionFailed())
                .addAllFailureCallStack(runtimeState.getFailureCallStackList());

        // Optional fields from runtime_state_blob
        if (!runtimeState.getFailureReason().isEmpty()) {
            builder.setFailureReason(runtimeState.getFailureReason());
        }
        if (runtimeState.hasInstructionOpcodeId()) {
            builder.setInstructionOpcodeId(runtimeState.getInstructionOpcodeId());
        }
        if (runtimeState.getInstructionRawArgumentsCount() > 0) {
            builder.addAllInstructionRawArguments(runtimeState.getInstructionRawArgumentsList());
        }
        if (runtimeState.hasInstructionEnergyCost()) {
            builder.setInstructionEnergyCost(runtimeState.getInstructionEnergyCost());
        }
        if (runtimeState.hasInstructionIpBeforeFetch()) {
            builder.setIpBeforeFetch(runtimeState.getInstructionIpBeforeFetch());
        }
        if (runtimeState.hasInstructionDvBeforeFetch()) {
            builder.setDvBeforeFetch(runtimeState.getInstructionDvBeforeFetch());
        }
        if (runtimeState.getInstructionRegisterValuesBeforeCount() > 0) {
            builder.putAllInstructionRegisterValuesBefore(runtimeState.getInstructionRegisterValuesBeforeMap());
        }

        return builder.build();
    }

    @Override
    public TickRange getAvailableTickRange(Connection conn) throws SQLException {
        String sql = "SELECT MIN(tick_number) as min_tick, MAX(tick_number) as max_tick FROM organism_states";

        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            if (!rs.next()) {
                return null;
            }

            long minTick = rs.getLong("min_tick");
            if (rs.wasNull()) {
                return null; // Table is empty
            }
            long maxTick = rs.getLong("max_tick");

            return new TickRange(minTick, maxTick);

        } catch (SQLException e) {
            // Table doesn't exist yet
            if (e.getErrorCode() == 42104 || e.getErrorCode() == 42102 ||
                    (e.getMessage() != null && e.getMessage().contains("Table") && e.getMessage().contains("not found"))) {
                return null;
            }
            throw e;
        }
    }
}
