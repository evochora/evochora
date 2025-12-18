package org.evochora.datapipeline.resources.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.OrganismNotFoundException;
import org.evochora.datapipeline.api.resources.database.TickNotFoundException;
import org.evochora.datapipeline.api.resources.database.dto.CellWithCoordinates;
import org.evochora.datapipeline.api.resources.database.dto.InstructionView;
import org.evochora.datapipeline.api.resources.database.dto.InstructionsView;
import org.evochora.datapipeline.api.resources.database.dto.OrganismRuntimeView;
import org.evochora.datapipeline.api.resources.database.dto.OrganismStaticInfo;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickDetails;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickSummary;
import org.evochora.datapipeline.api.resources.database.dto.SpatialRegion;
import org.evochora.datapipeline.resources.database.h2.IH2EnvStorageStrategy;
import org.evochora.datapipeline.resources.database.h2.IH2OrgStorageStrategy;
import org.evochora.datapipeline.utils.MoleculeDataUtils;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.MoleculeTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-request database reader for H2.
 * <p>
 * Holds a dedicated connection with schema already set.
 */
public class H2DatabaseReader implements IDatabaseReader {
    
    private static final Logger log = LoggerFactory.getLogger(H2DatabaseReader.class);
    
    /**
     * Guard to ensure that the instruction set is initialized exactly once per JVM.
     * <p>
     * <strong>Rationale:</strong> When the simulation engine is not running, the
     * Instruction registry may never be initialized. In that case, calls to
     * {@link Instruction#getInstructionNameById(int)} would always return
     * {@code "UNKNOWN"}. This affects the environment visualizer when it reads
     * historical environment data via database readers without having
     * started the simulation engine in the same process.
     * <p>
     * By lazily initializing the instruction set here, we ensure that opcode
     * names are available regardless of whether the simulation engine has been
     * constructed in the current JVM.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean INSTRUCTION_INITIALIZED =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    
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
    
    /**
     * Ensures that the instruction set is initialized.
     * <p>
     * This method is idempotent and thread-safe.
     */
    private static void ensureInstructionSetInitialized() {
        if (INSTRUCTION_INITIALIZED.compareAndSet(false, true)) {
            Instruction.init();
        }
    }
    
    @Override
    public List<CellWithCoordinates> readEnvironmentRegion(long tickNumber, SpatialRegion region) 
            throws SQLException, TickNotFoundException {
        ensureNotClosed();
        
        // Get metadata to extract environment properties
        SimulationMetadata metadata;
        try {
            metadata = getMetadata();
        } catch (org.evochora.datapipeline.api.resources.database.MetadataNotFoundException e) {
            throw new SQLException("Metadata not found for runId: " + runId, e);
        }
        EnvironmentProperties envProps = extractEnvironmentProperties(metadata);
        
        // Read cells via strategy
        List<org.evochora.datapipeline.api.contracts.CellState> cells = 
            envStrategy.readTick(connection, tickNumber, region, envProps);
        
        // Ensure instruction set is initialized before resolving opcode names
        ensureInstructionSetInitialized();
        
        // Convert flatIndex to coordinates and molecule type int to string
        return cells.stream()
            .map(cell -> {
                int[] coords = envProps.flatIndexToCoordinates(cell.getFlatIndex());

                int moleculeInt = cell.getMoleculeData();
                int moleculeType = moleculeInt & Config.TYPE_MASK;
                int moleculeValue = MoleculeDataUtils.extractSignedValue(moleculeInt);
                int marker = (moleculeInt & Config.MARKER_MASK) >> Config.MARKER_SHIFT;

                String moleculeTypeName = MoleculeTypeRegistry.typeToName(moleculeType);
                
                // For CODE molecules, resolve opcode name from value
                String opcodeName = null;
                if (moleculeType == Config.TYPE_CODE) {
                    opcodeName = Instruction.getInstructionNameById(moleculeValue);
                }
                return new CellWithCoordinates(
                    coords,
                    moleculeTypeName,
                    moleculeValue,
                    cell.getOwnerId(),
                    opcodeName,
                    marker
                );
            })
            .collect(java.util.stream.Collectors.toList());
    }


    private EnvironmentProperties extractEnvironmentProperties(SimulationMetadata metadata) {
        org.evochora.datapipeline.api.contracts.EnvironmentConfig envConfig = 
            metadata.getEnvironment();
        
        int[] shape = new int[envConfig.getShapeCount()];
        for (int i = 0; i < envConfig.getShapeCount(); i++) {
            shape[i] = envConfig.getShape(i);
        }
        
        // For now, assume all dimensions have same toroidal setting
        // Probably support per-dimension toroidal settings in future
        boolean isToroidal = envConfig.getToroidalCount() > 0 && envConfig.getToroidal(0);
        
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
        
        // Convert OrganismState to OrganismRuntimeView using the converter
        OrganismRuntimeView state = convertOrganismStateToRuntimeView(orgState, envDimensions);

        // Resolve "next" instruction from tick+1 if sampling_interval=1
        InstructionView nextInstruction = null;
        int samplingInterval = (int) metadata.getSamplingInterval();
        if (samplingInterval == 1) {
            try {
                OrganismRuntimeView nextState = readOrganismStateForTick(tickNumber + 1, organismId, envDimensions);
                if (nextState != null && nextState.instructions != null && nextState.instructions.last != null) {
                    nextInstruction = nextState.instructions.last;
                }
            } catch (OrganismNotFoundException e) {
                // tick+1 doesn't exist - nextInstruction remains null
            }
        }

        // Update state with resolved next instruction
        InstructionsView instructions = new InstructionsView(state.instructions.last, nextInstruction);
        OrganismRuntimeView stateWithInstructions = new OrganismRuntimeView(
                state.energy, state.ip, state.dv, state.dataPointers, state.activeDpIndex,
                state.dataRegisters, state.procedureRegisters, state.formalParamRegisters,
                state.locationRegisters, state.dataStack, state.locationStack, state.callStack,
                state.instructionFailed, state.failureReason, state.failureCallStack,
                instructions, state.entropyRegister, state.moleculeMarkerRegister);

        return new OrganismTickDetails(organismId, tickNumber, staticInfo, stateWithInstructions);
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
        InstructionsView instructions = new InstructionsView(lastInstruction, null);
        
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

    /**
     * Reads organism state for a specific tick (helper for reading tick+1).
     *
     * @param tickNumber  Tick number to read
     * @param organismId  Organism ID
     * @param envDimensions Environment dimensions for instruction resolution
     * @return OrganismRuntimeView or null if not found
     * @throws SQLException if database error occurs
     * @throws OrganismNotFoundException if organism state not found
     */
    private OrganismRuntimeView readOrganismStateForTick(long tickNumber, int organismId, int[] envDimensions)
            throws SQLException, OrganismNotFoundException {
        // Read organism state from strategy (BLOB-based for SingleBlobOrgStrategy)
        org.evochora.datapipeline.api.contracts.OrganismState orgState = 
                orgStrategy.readSingleOrganismState(connection, tickNumber, organismId);
        
        if (orgState == null) {
            throw new OrganismNotFoundException(
                    "No organism state for id " + organismId + " at tick " + tickNumber);
        }
        
        return convertOrganismStateToRuntimeView(orgState, envDimensions);
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

                return new OrganismStaticInfo(parentId, birthTick, programId, initialPos);
            }
        }
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

