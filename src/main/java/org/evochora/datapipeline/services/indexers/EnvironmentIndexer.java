package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareEnvironmentDataWriter;
import org.evochora.runtime.model.EnvironmentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Indexes environment cell states from TickData for efficient spatial queries.
 * <p>
 * This indexer:
 * <ul>
 *   <li>Reads TickData batches from storage (via topic notifications)</li>
 *   <li>Extracts CellState list (sparse - only non-empty cells)</li>
 *   <li>Writes to database with MERGE for 100% idempotency</li>
 *   <li>Supports dimension-agnostic schema (1D to N-D)</li>
 * </ul>
 * <p>
 * <strong>Resources Required:</strong>
 * <ul>
 *   <li>{@code storage} - IBatchStorageRead for reading TickData batches</li>
 *   <li>{@code topic} - ITopicReader for batch notifications</li>
 *   <li>{@code metadata} - IMetadataReader for simulation metadata</li>
 *   <li>{@code database} - IEnvironmentDataWriter for writing cells</li>
 * </ul>
 * <p>
 * <strong>Components Used:</strong>
 * <ul>
 *   <li>MetadataReadingComponent - waits for metadata before processing</li>
 *   <li>TickBufferingComponent - buffers cells for efficient batch writes</li>
 * </ul>
 * <p>
 * <strong>Competing Consumers:</strong> Multiple instances can run in parallel
 * using the same consumer group. Topic distributes batches across instances,
 * and MERGE ensures idempotent writes even with concurrent access.
 *
 * @param <ACK> The acknowledgment token type (implementation-specific)
 */
public class EnvironmentIndexer<ACK> extends AbstractBatchIndexer<ACK> {
    
    private static final Logger log = LoggerFactory.getLogger(EnvironmentIndexer.class);
    
    private final IResourceSchemaAwareEnvironmentDataWriter database;
    private EnvironmentProperties envProps;
    
    /**
     * Creates a new environment indexer.
     * <p>
     * Uses default components (METADATA + BUFFERING) from AbstractBatchIndexer.
     *
     * @param name Service name (must not be null/blank)
     * @param options Configuration for this indexer (must not be null)
     * @param resources Resources for this indexer (must not be null)
     */
    public EnvironmentIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.database = getRequiredResource("database", IResourceSchemaAwareEnvironmentDataWriter.class);
    }
    
    // Use default components: METADATA + BUFFERING
    // No override needed - AbstractBatchIndexer provides correct defaults
    
    /**
     * Prepares database tables for environment data storage.
     * <p>
     * Extracts environment properties from metadata and creates the environment_ticks table.
     * <strong>Idempotent:</strong> Safe to call multiple times (uses CREATE TABLE IF NOT EXISTS).
     *
     * @param runId The simulation run ID (schema already set by AbstractIndexer)
     * @throws Exception if table preparation fails
     */
    @Override
    protected void prepareTables(String runId) throws Exception {
        // Load metadata (provided by MetadataReadingComponent via getMetadata())
        SimulationMetadata metadata = getMetadata();
        
        // Extract environment properties for coordinate conversion
        this.envProps = extractEnvironmentProperties(metadata);
        
        // Create database table (idempotent)
        int dimensions = envProps.getWorldShape().length;
        database.createEnvironmentDataTable(dimensions);
        
        log.debug("Environment tables prepared: {} dimensions", dimensions);
    }
    
    /**
     * Flushes buffered ticks to the database.
     * <p>
     * Writes all ticks in ONE database batch with ONE commit for maximum performance.
     * <strong>Idempotency:</strong> MERGE ensures duplicate writes are safe.
     *
     * @param ticks Ticks to flush (1 for tick-by-tick mode, multiple for buffered mode)
     * @throws Exception if flush fails
     */
    @Override
    protected void flushTicks(List<TickData> ticks) throws Exception {
        if (ticks.isEmpty()) {
            log.debug("No ticks to flush");
            return;
        }

        // Write ALL ticks in one JDBC batch with one commit
        database.writeEnvironmentCells(ticks, envProps);
        
        int totalCells = ticks.stream()
            .mapToInt(t -> t.getCellsList().size())
            .sum();
        
        log.debug("Flushed {} cells from {} ticks", totalCells, ticks.size());
    }
    
    /**
     * Extracts EnvironmentProperties from SimulationMetadata.
     * <p>
     * Parses world shape (dimensions) and topology (toroidal) from metadata.
     *
     * @param metadata Simulation metadata containing environment configuration
     * @return EnvironmentProperties for coordinate conversion
     */
    private EnvironmentProperties extractEnvironmentProperties(SimulationMetadata metadata) {
        // Extract world shape from metadata
        int[] worldShape = metadata.getEnvironment().getShapeList().stream()
            .mapToInt(Integer::intValue)
            .toArray();
        
        // Extract topology - check if ALL dimensions are toroidal
        // (In practice, all dimensions have same topology for now)
        boolean isToroidal = !metadata.getEnvironment().getToroidalList().isEmpty() 
            && metadata.getEnvironment().getToroidal(0);
        
        return new EnvironmentProperties(worldShape, isToroidal);
    }
}

