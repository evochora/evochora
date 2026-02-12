package org.evochora.datapipeline.resources.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.OrganismNotFoundException;
import org.evochora.datapipeline.api.resources.database.TickNotFoundException;
import org.evochora.datapipeline.api.resources.database.dto.InstructionView;
import org.evochora.datapipeline.api.resources.database.dto.InstructionsView;
import org.evochora.datapipeline.api.resources.database.dto.LineageEntry;
import org.evochora.datapipeline.api.resources.database.dto.OrganismRuntimeView;
import org.evochora.datapipeline.api.resources.database.dto.OrganismStaticInfo;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickDetails;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickSummary;
import org.evochora.datapipeline.resources.database.h2.IH2EnvStorageStrategy;
import org.evochora.datapipeline.resources.database.h2.IH2OrgStorageStrategy;

import org.evochora.runtime.model.EnvironmentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Per-request database reader for H2.
 * <p>
 * Holds a dedicated connection with schema already set.
 */
public class H2DatabaseReader implements IDatabaseReader {
    
    private static final Logger log = LoggerFactory.getLogger(H2DatabaseReader.class);
    
    private final Connection connection;
    private final H2Database database;
    private final IH2EnvStorageStrategy envStrategy;
    private final IH2OrgStorageStrategy orgStrategy;
    private final String runId;
    private boolean closed = false;
    
    public H2DatabaseReader(Connection connection, H2Database database, 
                           IH2EnvStorageStrategy envStrategy, 
                           IH2OrgStorageStrategy orgStrategy,
                           String runId) {
        this.connection = connection;
        this.database = database;
        this.envStrategy = envStrategy;
        this.orgStrategy = orgStrategy;
        this.runId = runId;
    }
    
    @Override
    public TickDataChunk readChunkContaining(long tickNumber) throws SQLException, TickNotFoundException {
        ensureNotClosed();
        return envStrategy.readChunkContaining(connection, tickNumber);
    }
    
    private EnvironmentProperties extractEnvironmentProperties(SimulationMetadata metadata) {
        // Parse environment config from resolvedConfigJson
        Config resolvedConfig = ConfigFactory.parseString(metadata.getResolvedConfigJson());

        int[] shape = resolvedConfig.getIntList("environment.shape").stream()
            .mapToInt(i -> i).toArray();
        boolean isToroidal = "TORUS".equalsIgnoreCase(
            resolvedConfig.getString("environment.topology"));

        return new EnvironmentProperties(shape, isToroidal);
    }
    
    @Override
    public SimulationMetadata getMetadata() throws SQLException, org.evochora.datapipeline.api.resources.database.MetadataNotFoundException {
        ensureNotClosed();
        return database.getMetadataInternal(connection, runId);
    }
    
    @Override
    public boolean hasMetadata() throws SQLException {
        ensureNotClosed();
        return database.hasMetadataInternal(connection, runId);
    }
    
    @Override
    public org.evochora.datapipeline.api.resources.database.dto.TickRange getTickRange() throws SQLException {
        ensureNotClosed();
        return database.getTickRangeInternal(connection, runId);
    }
    
    @Override
    public org.evochora.datapipeline.api.resources.database.dto.TickRange getOrganismTickRange() throws SQLException {
        ensureNotClosed();
        return database.getOrganismTickRangeInternal(connection, runId);
    }

    @Override
    public List<OrganismTickSummary> readOrganismsAtTick(long tickNumber) throws SQLException {
        ensureNotClosed();

        if (tickNumber < 0) {
            throw new IllegalArgumentException("tickNumber must be non-negative");
        }

        // Delegate to organism storage strategy
        return orgStrategy.readOrganismsAtTick(connection, tickNumber);
    }

    @Override
    public int readTotalOrganismsCreated(long tickNumber) throws SQLException {
        ensureNotClosed();
        return orgStrategy.readTotalOrganismsCreated(connection, tickNumber);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Queries the {@code organisms} static table directly (strategy-independent).
     * Self-referencing rows (child genome equals parent genome, i.e. no mutation) are excluded
     * by the SQL filter. When multiple organisms share the same genome hash, the first by
     * {@code organism_id} determines the parent mapping.
     * <p>
     * Not thread-safe — each {@link H2DatabaseReader} instance holds a dedicated connection
     * and must not be shared across threads.
     */
    @Override
    public Map<Long, Long> readGenomeLineageTree(long tickNumber) throws SQLException {
        ensureNotClosed();

        String sql = """
            SELECT c.genome_hash, p.genome_hash AS parent_genome_hash
            FROM organisms c
            LEFT JOIN organisms p ON c.parent_id = p.organism_id
            WHERE c.birth_tick <= ? AND c.genome_hash != 0
              AND (p.genome_hash IS NULL OR c.genome_hash != p.genome_hash)
            ORDER BY c.organism_id
            """;

        Map<Long, Long> tree = new LinkedHashMap<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, tickNumber);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long genomeHash = rs.getLong("genome_hash");
                    if (tree.containsKey(genomeHash)) continue;
                    long parentGenomeHash = rs.getLong("parent_genome_hash");
                    tree.put(genomeHash, (rs.wasNull() || parentGenomeHash == 0) ? null : parentGenomeHash);
                }
            }
        }
        return tree;
    }

    @Override
    public OrganismTickDetails readOrganismDetails(long tickNumber, int organismId)
            throws SQLException, OrganismNotFoundException {
        ensureNotClosed();

        if (tickNumber < 0) {
            throw new IllegalArgumentException("tickNumber must be non-negative");
        }
        if (organismId < 0) {
            throw new IllegalArgumentException("organismId must be non-negative");
        }

        OrganismStaticInfo staticInfo = readOrganismStaticInfo(organismId);
        if (staticInfo == null) {
            throw new OrganismNotFoundException("No organism metadata for id " + organismId);
        }

        // Get metadata to extract environment dimensions for instruction resolution
        SimulationMetadata metadata;
        try {
            metadata = getMetadata();
        } catch (org.evochora.datapipeline.api.resources.database.MetadataNotFoundException e) {
            throw new SQLException("Metadata not found for runId: " + runId, e);
        }
        EnvironmentProperties envProps = extractEnvironmentProperties(metadata);
        int[] envDimensions = envProps.getWorldShape();

        // Read organism state from strategy (BLOB-based for SingleBlobOrgStrategy)
        org.evochora.datapipeline.api.contracts.OrganismState orgState = 
                orgStrategy.readSingleOrganismState(connection, tickNumber, organismId);
        
        if (orgState == null) {
            throw new OrganismNotFoundException(
                    "No organism state for id " + organismId + " at tick " + tickNumber);
        }
        
        // Convert OrganismState to OrganismRuntimeView (includes both last and next instruction from protobuf)
        OrganismRuntimeView state = convertOrganismStateToRuntimeView(orgState, envDimensions);

        return new OrganismTickDetails(organismId, tickNumber, staticInfo, state);
    }
    
    /**
     * Converts an OrganismState Protobuf message to OrganismRuntimeView DTO.
     * <p>
     * This conversion extracts all fields from the Protobuf and uses OrganismStateConverter
     * for complex nested structures.
     *
     * @param orgState OrganismState Protobuf object
     * @param envDimensions Environment dimensions for instruction resolution
     * @return OrganismRuntimeView DTO
     * @throws SQLException if conversion fails
     */
    private OrganismRuntimeView convertOrganismStateToRuntimeView(
            org.evochora.datapipeline.api.contracts.OrganismState orgState,
            int[] envDimensions) throws SQLException {
        
        int energy = orgState.getEnergy();
        int[] ip = OrganismStateConverter.vectorToArray(orgState.getIp());
        int[] dv = OrganismStateConverter.vectorToArray(orgState.getDv());
        
        // Convert data pointers
        int[][] dataPointers = new int[orgState.getDataPointersCount()][];
        for (int i = 0; i < orgState.getDataPointersCount(); i++) {
            dataPointers[i] = OrganismStateConverter.vectorToArray(orgState.getDataPointers(i));
        }
        int activeDpIndex = orgState.getActiveDpIndex();
        
        // Convert registers
        java.util.List<org.evochora.datapipeline.api.resources.database.dto.RegisterValueView> dataRegs = 
                new java.util.ArrayList<>();
        for (var rv : orgState.getDataRegistersList()) {
            dataRegs.add(OrganismStateConverter.convertRegisterValue(rv));
        }
        
        java.util.List<org.evochora.datapipeline.api.resources.database.dto.RegisterValueView> procRegs = 
                new java.util.ArrayList<>();
        for (var rv : orgState.getProcedureRegistersList()) {
            procRegs.add(OrganismStateConverter.convertRegisterValue(rv));
        }
        
        java.util.List<org.evochora.datapipeline.api.resources.database.dto.RegisterValueView> fprRegs = 
                new java.util.ArrayList<>();
        for (var rv : orgState.getFormalParamRegistersList()) {
            fprRegs.add(OrganismStateConverter.convertRegisterValue(rv));
        }
        
        java.util.List<int[]> locationRegs = new java.util.ArrayList<>();
        for (var v : orgState.getLocationRegistersList()) {
            locationRegs.add(OrganismStateConverter.vectorToArray(v));
        }
        
        // Convert stacks
        java.util.List<org.evochora.datapipeline.api.resources.database.dto.RegisterValueView> dataStack = 
                new java.util.ArrayList<>();
        for (var rv : orgState.getDataStackList()) {
            dataStack.add(OrganismStateConverter.convertRegisterValue(rv));
        }
        
        java.util.List<int[]> locationStack = new java.util.ArrayList<>();
        for (var v : orgState.getLocationStackList()) {
            locationStack.add(OrganismStateConverter.vectorToArray(v));
        }
        
        java.util.List<org.evochora.datapipeline.api.resources.database.dto.ProcFrameView> callStack = 
                new java.util.ArrayList<>();
        for (var frame : orgState.getCallStackList()) {
            callStack.add(OrganismStateConverter.convertProcFrame(frame));
        }
        
        java.util.List<org.evochora.datapipeline.api.resources.database.dto.ProcFrameView> failureStack = 
                new java.util.ArrayList<>();
        for (var frame : orgState.getFailureCallStackList()) {
            failureStack.add(OrganismStateConverter.convertProcFrame(frame));
        }
        
        // Resolve instruction
        InstructionView lastInstruction = null;
        if (orgState.hasInstructionOpcodeId() && envDimensions != null) {
            // Read register values before execution
            java.util.Map<Integer, org.evochora.datapipeline.api.resources.database.dto.RegisterValueView> registerValuesBefore = 
                    new java.util.HashMap<>();
            for (var entry : orgState.getInstructionRegisterValuesBeforeMap().entrySet()) {
                registerValuesBefore.put(entry.getKey(), OrganismStateConverter.convertRegisterValue(entry.getValue()));
            }
            
            lastInstruction = OrganismStateConverter.resolveInstructionView(
                    orgState.getInstructionOpcodeId(),
                    orgState.getInstructionRawArgumentsList(),
                    orgState.hasInstructionEnergyCost() ? orgState.getInstructionEnergyCost() : 0,
                    orgState.hasInstructionEntropyDelta() ? orgState.getInstructionEntropyDelta() : 0,
                    orgState.hasIpBeforeFetch() ? OrganismStateConverter.vectorToArray(orgState.getIpBeforeFetch()) : null,
                    orgState.hasDvBeforeFetch() ? OrganismStateConverter.vectorToArray(orgState.getDvBeforeFetch()) : null,
                    orgState.getInstructionFailed(),
                    orgState.hasFailureReason() ? orgState.getFailureReason() : null,
                    dataRegs, procRegs, fprRegs, locationRegs, envDimensions, registerValuesBefore
            );
        }

        // Resolve next instruction from protobuf preview data
        InstructionView nextInstruction = null;
        if (orgState.hasNextInstructionOpcodeId() && envDimensions != null) {
            java.util.Map<Integer, org.evochora.datapipeline.api.resources.database.dto.RegisterValueView> nextRegValues =
                    new java.util.HashMap<>();
            for (var entry : orgState.getNextInstructionRegisterValuesBeforeMap().entrySet()) {
                nextRegValues.put(entry.getKey(), OrganismStateConverter.convertRegisterValue(entry.getValue()));
            }

            nextInstruction = OrganismStateConverter.resolveInstructionView(
                    orgState.getNextInstructionOpcodeId(),
                    orgState.getNextInstructionRawArgumentsList(),
                    0,
                    0,
                    ip,
                    dv,
                    false,
                    null,
                    dataRegs, procRegs, fprRegs, locationRegs, envDimensions, nextRegValues
            );
        }
        InstructionsView instructions = new InstructionsView(lastInstruction, nextInstruction);

        return new OrganismRuntimeView(
                energy, ip, dv, dataPointers, activeDpIndex,
                dataRegs, procRegs, fprRegs, locationRegs,
                dataStack, locationStack, callStack,
                orgState.getInstructionFailed(),
                orgState.hasFailureReason() ? orgState.getFailureReason() : null,
                failureStack, instructions,
                orgState.getEntropyRegister(),
                orgState.getMoleculeMarkerRegister()
        );
    }

    private OrganismStaticInfo readOrganismStaticInfo(int organismId) throws SQLException {
        String sql = """
            SELECT parent_id, birth_tick, program_id, initial_position
            FROM organisms
            WHERE organism_id = ?
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, organismId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }

                Integer parentId = rs.getObject("parent_id") != null
                        ? rs.getInt("parent_id")
                        : null;
                long birthTick = rs.getLong("birth_tick");
                String programId = rs.getString("program_id");
                byte[] initialPosBytes = rs.getBytes("initial_position");
                int[] initialPos = OrganismStateConverter.decodeVector(initialPosBytes);

                List<LineageEntry> lineage = readLineage(organismId);
                return new OrganismStaticInfo(parentId, birthTick, programId, initialPos, lineage);
            }
        }
    }

    /**
     * Reads the ancestry chain for an organism via recursive CTE.
     * Returns direct parent first, oldest ancestor last. Empty list for initial organisms.
     *
     * @param organismId The organism to trace ancestry for.
     * @return Ancestry chain (never null).
     * @throws SQLException if database query fails.
     */
    private List<LineageEntry> readLineage(int organismId) throws SQLException {
        String sql = """
            WITH RECURSIVE ancestors(org_id, depth) AS (
                SELECT parent_id, 1
                FROM organisms WHERE organism_id = ?
                UNION ALL
                SELECT o.parent_id, a.depth + 1
                FROM ancestors a
                JOIN organisms o ON o.organism_id = a.org_id
                WHERE o.parent_id IS NOT NULL AND a.depth < 100
            )
            SELECT o.organism_id, o.genome_hash
            FROM ancestors a
            JOIN organisms o ON o.organism_id = a.org_id
            ORDER BY a.depth ASC
            """;

        List<LineageEntry> lineage = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, organismId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    lineage.add(new LineageEntry(rs.getInt("organism_id"), rs.getLong("genome_hash")));
                }
            }
        }
        return lineage;
    }

    @Override
    public void close() {
        if (closed) return;
        
        try {
            connection.close();
            closed = true;
        } catch (SQLException e) {
            log.warn("Failed to close database reader connection");
        }
    }
    
    private void ensureNotClosed() {
        if (closed) {
            throw new IllegalStateException("Reader already closed");
        }
    }
}

