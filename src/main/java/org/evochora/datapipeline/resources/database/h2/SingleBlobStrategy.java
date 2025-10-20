package org.evochora.datapipeline.resources.database.h2;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.CellStateList;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.utils.H2SchemaUtil;
import org.evochora.datapipeline.utils.compression.CompressionCodecFactory;
import org.evochora.datapipeline.utils.compression.ICompressionCodec;
import org.evochora.runtime.model.EnvironmentProperties;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * SingleBlobStrategy: Stores all cells of a tick in a single BLOB.
 * <p>
 * <strong>Storage:</strong> ~1 KB per tick (with compression)
 * <ul>
 *   <li>100×100 environment, 10% occupancy = 1000 cells/tick</li>
 *   <li>1000 cells × 20 bytes (protobuf) = 20 KB raw</li>
 *   <li>With compression: ~1-2 KB per tick</li>
 *   <li>10^6 ticks = 1-2 GB total ✅</li>
 * </ul>
 * <p>
 * <strong>Query Performance:</strong> Slower (must deserialize entire tick)
 * <p>
 * <strong>Write Performance:</strong> Excellent (one row per tick)
 * <p>
 * <strong>Best For:</strong> Large runs where storage size is critical.
 * <p>
 * <strong>PreparedStatement Caching:</strong> Caches PreparedStatement per connection.
 * When connection changes (pool rotation), automatically recreates the statement.
 * This provides performance benefits while remaining pool-safe.
 */
public class SingleBlobStrategy extends AbstractH2EnvStorageStrategy {
    
    final ICompressionCodec codec;
    String mergeSql;  // SQL string
    private PreparedStatement cachedStmt;  // Cached PreparedStatement
    private Connection cachedConn;  // Track which connection owns the cached stmt
    
    /**
     * Creates SingleBlobStrategy with optional compression.
     * 
     * @param options Config with optional compression block
     */
    public SingleBlobStrategy(Config options) {
        super(options);
        this.codec = CompressionCodecFactory.create(options);
        log.debug("SingleBlobStrategy initialized with compression: {}", codec.getName());
    }
    
    @Override
    public void createSchema(Connection conn, int dimensions) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // Use H2SchemaUtil for concurrent-safe DDL execution
            H2SchemaUtil.executeDdlIfNotExists(
                stmt,
            "CREATE TABLE IF NOT EXISTS environment_ticks (" +
            "  tick_number BIGINT PRIMARY KEY," +
            "  cells_blob BYTEA NOT NULL" +
            ")",
                "environment_ticks"
            );
            
            // ALSO use H2SchemaUtil for index (same race condition!)
            H2SchemaUtil.executeDdlIfNotExists(
                stmt,
                "CREATE INDEX IF NOT EXISTS idx_env_tick ON environment_ticks (tick_number)",
                "idx_env_tick"
            );
        }
        
        // Cache SQL string
        this.mergeSql = "MERGE INTO environment_ticks (tick_number, cells_blob) " +
                       "KEY (tick_number) VALUES (?, ?)";
        
        log.debug("Environment ticks table created for {} dimensions", dimensions);
    }
    
    @Override
    public void writeTicks(Connection conn, List<TickData> ticks, 
                          EnvironmentProperties envProps) throws SQLException {
        if (ticks.isEmpty()) {
            return;
        }
        
        // Filter out ticks with empty cell lists (shouldn't happen in practice)
        List<TickData> validTicks = ticks.stream()
            .filter(tick -> {
                if (tick.getCellsList().isEmpty()) {
                    log.warn("Tick {} has empty cell list - skipping database write", tick.getTickNumber());
                    return false;
                }
                return true;
            })
            .toList();
        
        if (validTicks.isEmpty()) {
            log.warn("All {} ticks had empty cell lists - no database writes performed", ticks.size());
            return;
        }
        
        // Connection-safe PreparedStatement caching
        // If connection changed (pool rotation), recreate PreparedStatement
        if (cachedStmt == null || cachedConn != conn) {
            cachedStmt = conn.prepareStatement(mergeSql);
            cachedConn = conn;
            log.debug("Created new PreparedStatement for connection");
        }
        
        cachedStmt.clearBatch();  // Clear previous batch state
        
        for (TickData tick : validTicks) {
            byte[] cellsBlob = serializeTickCells(tick);
            
            cachedStmt.setLong(1, tick.getTickNumber());
            cachedStmt.setBytes(2, cellsBlob);
            cachedStmt.addBatch();
        }
        
        cachedStmt.executeBatch();
        
        log.debug("Wrote {} ticks to environment_ticks table (skipped {} empty ticks)", 
                 validTicks.size(), ticks.size() - validTicks.size());
    }
    
    /**
     * Serializes tick cells to compressed BLOB using Protobuf CellStateList.
     * <p>
     * <strong>Precondition:</strong> tick.getCellsList() must not be empty.
     * This method is only called for ticks that passed the empty check in writeTicks().
     * 
     * @param tick The tick data containing cells to serialize (must have cells)
     * @return Compressed byte array ready for BLOB storage
     * @throws SQLException if serialization fails
     */
    private byte[] serializeTickCells(TickData tick) throws SQLException {
        try {
            CellStateList cellsList = CellStateList.newBuilder()
                .addAllCells(tick.getCellsList())
                .build();
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (OutputStream compressed = codec.wrapOutputStream(baos)) {
                cellsList.writeTo(compressed);
            }
            
            return baos.toByteArray();
            
        } catch (IOException e) {
            throw new SQLException("Failed to serialize cells for tick: " + tick.getTickNumber(), e);
        }
    }
}
