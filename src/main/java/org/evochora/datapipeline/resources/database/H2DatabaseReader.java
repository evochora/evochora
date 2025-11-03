package org.evochora.datapipeline.resources.database;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.database.*;
import org.evochora.datapipeline.resources.database.H2Database;
import org.evochora.datapipeline.resources.database.h2.IH2EnvStorageStrategy;
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
                return new CellWithCoordinates(
                    coords,
                    moleculeTypeName,
                    cell.getMoleculeValue(),
                    cell.getOwnerId()
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
