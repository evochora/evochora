package org.evochora.datapipeline.resources.database;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.OrganismRuntimeState;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.datapipeline.api.resources.database.*;
import org.evochora.datapipeline.resources.database.h2.IH2EnvStorageStrategy;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.MoleculeTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

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
     * historical environment data via {@link H2DatabaseReader} without having
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
    private final String runId;
    private boolean closed = false;
    
    public H2DatabaseReader(Connection connection, H2Database database, 
                           IH2EnvStorageStrategy envStrategy, String runId) {
        this.connection = connection;
        this.database = database;
        this.envStrategy = envStrategy;
        this.runId = runId;
    }
    
    @Override
    public List<CellWithCoordinates> readEnvironmentRegion(long tickNumber, SpatialRegion region) 
            throws SQLException {
        ensureNotClosed();
        ensureInstructionSetInitialized();
        
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
        
        // Convert flatIndex to coordinates and molecule type int to string
        return cells.stream()
            .map(cell -> {
                int[] coords = envProps.flatIndexToCoordinates(cell.getFlatIndex());
                String moleculeTypeName = MoleculeTypeRegistry.typeToName(cell.getMoleculeType());
                // For CODE molecules, resolve opcode name from value
                String opcodeName = null;
                if (cell.getMoleculeType() == Config.TYPE_CODE) {
                    opcodeName = Instruction.getInstructionNameById(cell.getMoleculeValue());
                }
                return new CellWithCoordinates(
                    coords,
                    moleculeTypeName,
                    cell.getMoleculeValue(),
                    cell.getOwnerId(),
                    opcodeName
                );
            })
            .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Lazily initializes the instruction set for this JVM if it has not yet been initialized.
     * <p>
     * This method is intentionally idempotent and thread-safe. It uses an
     * {@link java.util.concurrent.atomic.AtomicBoolean} to ensure that
     * {@link Instruction#init()} is called at most once, even under concurrent access.
     */
    private void ensureInstructionSetInitialized() {
        if (INSTRUCTION_INITIALIZED.compareAndSet(false, true)) {
            Instruction.init();
        }
    }

    private EnvironmentProperties extractEnvironmentProperties(SimulationMetadata metadata) {
        org.evochora.datapipeline.api.contracts.EnvironmentConfig envConfig = 
            metadata.getEnvironment();
        
        int[] shape = new int[envConfig.getShapeCount()];
        for (int i = 0; i < envConfig.getShapeCount(); i++) {
            shape[i] = envConfig.getShape(i);
        }
        
        // For now, assume all dimensions have same toroidal setting
        // TODO: Support per-dimension toroidal settings in future
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
    public org.evochora.datapipeline.api.resources.database.TickRange getTickRange() throws SQLException {
        ensureNotClosed();
        return database.getTickRangeInternal(connection, runId);
    }

    @Override
    public List<OrganismTickSummary> readOrganismsAtTick(long tickNumber) throws SQLException {
        ensureNotClosed();

        if (tickNumber < 0) {
            throw new IllegalArgumentException("tickNumber must be non-negative");
        }

        String sql = """
            SELECT organism_id, energy, ip, dv, data_pointers, active_dp_index
            FROM organism_states
            WHERE tick_number = ?
            ORDER BY organism_id
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
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

                    int[] ip = decodeVector(ipBytes);
                    int[] dv = decodeVector(dvBytes);
                    int[][] dataPointers = decodeDataPointers(dpBytes);

                    result.add(new OrganismTickSummary(
                            organismId,
                            energy,
                            ip,
                            dv,
                            dataPointers,
                            activeDpIndex
                    ));
                }
                return result;
            }
        }
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

        String sql = """
            SELECT energy, ip, dv, data_pointers, active_dp_index, runtime_state_blob
            FROM organism_states
            WHERE tick_number = ? AND organism_id = ?
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, tickNumber);
            stmt.setInt(2, organismId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new OrganismNotFoundException(
                            "No organism state for id " + organismId + " at tick " + tickNumber);
                }

                int energy = rs.getInt("energy");
                byte[] ipBytes = rs.getBytes("ip");
                byte[] dvBytes = rs.getBytes("dv");
                byte[] dpBytes = rs.getBytes("data_pointers");
                int activeDpIndex = rs.getInt("active_dp_index");
                byte[] blobBytes = rs.getBytes("runtime_state_blob");

                int[] ip = decodeVector(ipBytes);
                int[] dv = decodeVector(dvBytes);
                int[][] dataPointers = decodeDataPointers(dpBytes);
                OrganismRuntimeView state = decodeRuntimeState(
                        energy, ip, dv, dataPointers, activeDpIndex, blobBytes);

                return new OrganismTickDetails(organismId, tickNumber, staticInfo, state);
            }
        }
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
                int[] initialPos = decodeVector(initialPosBytes);

                return new OrganismStaticInfo(parentId, birthTick, programId, initialPos);
            }
        }
    }

    private int[] decodeVector(byte[] bytes) throws SQLException {
        if (bytes == null) {
            return null;
        }
        try {
            Vector vec = Vector.parseFrom(bytes);
            int[] result = new int[vec.getComponentsCount()];
            for (int i = 0; i < result.length; i++) {
                result[i] = vec.getComponents(i);
            }
            return result;
        } catch (Exception e) {
            throw new SQLException("Failed to decode vector from bytes", e);
        }
    }

    private int[][] decodeDataPointers(byte[] bytes) throws SQLException {
        if (bytes == null) {
            return new int[0][];
        }
        try {
            org.evochora.datapipeline.api.contracts.DataPointerList list =
                    org.evochora.datapipeline.api.contracts.DataPointerList.parseFrom(bytes);
            int[][] result = new int[list.getDataPointersCount()][];
            for (int i = 0; i < list.getDataPointersCount(); i++) {
                Vector v = list.getDataPointers(i);
                int[] components = new int[v.getComponentsCount()];
                for (int j = 0; j < components.length; j++) {
                    components[j] = v.getComponents(j);
                }
                result[i] = components;
            }
            return result;
        } catch (Exception e) {
            throw new SQLException("Failed to decode data pointers from bytes", e);
        }
    }

    private OrganismRuntimeView decodeRuntimeState(int energy,
                                                   int[] ip,
                                                   int[] dv,
                                                   int[][] dataPointers,
                                                   int activeDpIndex,
                                                   byte[] blobBytes) throws SQLException {
        if (blobBytes == null) {
            throw new SQLException("runtime_state_blob is null");
        }

        org.evochora.datapipeline.utils.compression.ICompressionCodec codec =
                org.evochora.datapipeline.utils.compression.CompressionCodecFactory
                        .detectFromMagicBytes(blobBytes);

        OrganismRuntimeState state;
        try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(blobBytes);
             java.io.InputStream in = codec.wrapInputStream(bais)) {
            state = OrganismRuntimeState.parseFrom(in);
        } catch (Exception e) {
            throw new SQLException("Failed to decode OrganismRuntimeState", e);
        }

        List<RegisterValueView> dataRegs = new ArrayList<>(state.getDataRegistersCount());
        for (org.evochora.datapipeline.api.contracts.RegisterValue rv : state.getDataRegistersList()) {
            dataRegs.add(convertRegisterValue(rv));
        }

        List<RegisterValueView> procRegs = new ArrayList<>(state.getProcedureRegistersCount());
        for (org.evochora.datapipeline.api.contracts.RegisterValue rv : state.getProcedureRegistersList()) {
            procRegs.add(convertRegisterValue(rv));
        }

        List<RegisterValueView> fprRegs = new ArrayList<>(state.getFormalParamRegistersCount());
        for (org.evochora.datapipeline.api.contracts.RegisterValue rv : state.getFormalParamRegistersList()) {
            fprRegs.add(convertRegisterValue(rv));
        }

        List<int[]> locationRegs = new ArrayList<>(state.getLocationRegistersCount());
        for (Vector v : state.getLocationRegistersList()) {
            locationRegs.add(vectorToArray(v));
        }

        List<RegisterValueView> dataStack = new ArrayList<>(state.getDataStackCount());
        for (org.evochora.datapipeline.api.contracts.RegisterValue rv : state.getDataStackList()) {
            dataStack.add(convertRegisterValue(rv));
        }

        List<int[]> locationStack = new ArrayList<>(state.getLocationStackCount());
        for (Vector v : state.getLocationStackList()) {
            locationStack.add(vectorToArray(v));
        }

        List<ProcFrameView> callStack = new ArrayList<>(state.getCallStackCount());
        for (org.evochora.datapipeline.api.contracts.ProcFrame frame : state.getCallStackList()) {
            callStack.add(convertProcFrame(frame));
        }

        List<ProcFrameView> failureStack = new ArrayList<>(state.getFailureCallStackCount());
        for (org.evochora.datapipeline.api.contracts.ProcFrame frame : state.getFailureCallStackList()) {
            failureStack.add(convertProcFrame(frame));
        }

        return new OrganismRuntimeView(
                energy,
                ip,
                dv,
                dataPointers,
                activeDpIndex,
                dataRegs,
                procRegs,
                fprRegs,
                locationRegs,
                dataStack,
                locationStack,
                callStack,
                state.getInstructionFailed(),
                state.getFailureReason(),
                failureStack
        );
    }

    private int[] vectorToArray(Vector v) {
        int[] result = new int[v.getComponentsCount()];
        for (int i = 0; i < result.length; i++) {
            result[i] = v.getComponents(i);
        }
        return result;
    }

    private RegisterValueView convertRegisterValue(
            org.evochora.datapipeline.api.contracts.RegisterValue rv) {
        if (rv.hasScalar()) {
            int raw = rv.getScalar();
            Molecule molecule = Molecule.fromInt(raw);
            int typeId = molecule.type();
            String typeName = MoleculeTypeRegistry.typeToName(typeId);
            int value = molecule.toScalarValue();
            return RegisterValueView.molecule(raw, typeId, typeName, value);
        }
        if (rv.hasVector()) {
            return RegisterValueView.vector(vectorToArray(rv.getVector()));
        }
        // Fallback: treat as MOLECULE with raw=0 CODE:0
        Molecule molecule = Molecule.fromInt(0);
        int typeId = molecule.type();
        String typeName = MoleculeTypeRegistry.typeToName(typeId);
        int value = molecule.toScalarValue();
        return RegisterValueView.molecule(0, typeId, typeName, value);
    }

    private ProcFrameView convertProcFrame(
            org.evochora.datapipeline.api.contracts.ProcFrame frame) {
        String procName = frame.getProcName();
        int[] absIp = vectorToArray(frame.getAbsoluteReturnIp());

        List<RegisterValueView> savedPrs = new ArrayList<>(frame.getSavedPrsCount());
        for (org.evochora.datapipeline.api.contracts.RegisterValue rv : frame.getSavedPrsList()) {
            savedPrs.add(convertRegisterValue(rv));
        }

        List<RegisterValueView> savedFprs = new ArrayList<>(frame.getSavedFprsCount());
        for (org.evochora.datapipeline.api.contracts.RegisterValue rv : frame.getSavedFprsList()) {
            savedFprs.add(convertRegisterValue(rv));
        }

        java.util.Map<Integer, Integer> bindings = new java.util.HashMap<>();
        for (java.util.Map.Entry<Integer, Integer> entry : frame.getFprBindingsMap().entrySet()) {
            bindings.put(entry.getKey(), entry.getValue());
        }

        return new ProcFrameView(procName, absIp, savedPrs, savedFprs, bindings);
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
