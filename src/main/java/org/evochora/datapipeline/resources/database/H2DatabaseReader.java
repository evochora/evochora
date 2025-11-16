package org.evochora.datapipeline.resources.database;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.database.*;
import org.evochora.datapipeline.resources.database.H2Database;
import org.evochora.datapipeline.resources.database.h2.IH2EnvStorageStrategy;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.MoleculeTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
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
