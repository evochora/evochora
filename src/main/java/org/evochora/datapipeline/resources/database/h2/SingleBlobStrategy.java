package org.evochora.datapipeline.resources.database.h2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.evochora.datapipeline.api.contracts.CellStateList;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.database.TickNotFoundException;
import org.evochora.datapipeline.api.resources.database.dto.SpatialRegion;
import org.evochora.datapipeline.utils.H2SchemaUtil;
import org.evochora.datapipeline.utils.compression.CompressionCodecFactory;
import org.evochora.datapipeline.utils.compression.ICompressionCodec;
import org.evochora.runtime.model.EnvironmentProperties;

import com.typesafe.config.Config;

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
 * <p>
 * <strong>Future Optimization: ChunkedBlobStrategy with Parallel Decompression</strong>
 * <p>
 * For very large environments (10k×10k+), a ChunkedBlobStrategy could partition
 * the environment into spatial chunks, enabling parallel decompression and selective
 * chunk loading for viewport queries:
 * <pre>{@code
 * // ChunkedBlobStrategy concept (NOT IMPLEMENTED):
 * public List<CellState> readTick(Connection conn, long tick, SpatialRegion region) {
 *     // 1. Determine which chunks overlap with region
 *     List<ChunkId> relevantChunks = getChunksForRegion(region, envProps);
 *     
 *     // 2. Read relevant chunks in parallel (not entire tick!)
 *     List<CompletableFuture<byte[]>> futures = relevantChunks.stream()
 *         .map(chunkId -> CompletableFuture.supplyAsync(() -> {
 *             return readChunkBlob(conn, tick, chunkId);  // SQL per chunk
 *         }, decompressorPool))
 *         .toList();
 *     
 *     // 3. Decompress chunks in parallel
 *     List<byte[]> decompressedChunks = futures.stream()
 *         .map(CompletableFuture::join)
 *         .toList();
 *     
 *     // 4. Deserialize and merge (parallel possible)
 *     return decompressedChunks.parallelStream()
 *         .map(bytes -> CellStateList.parseFrom(bytes).getCellsList())
 *         .flatMap(List::stream)
 *         .filter(cell -> isInRegion(cell, region))
 *         .toList();
 * }
 * }</pre>
 * <strong>Benefits of ChunkedBlobStrategy:</strong>
 * <ul>
 *   <li>Selective chunk loading: Only read chunks overlapping viewport (not entire tick)</li>
 *   <li>Parallel decompression: N chunks = N cores = ~N× speedup</li>
 *   <li>Better scalability: 10k×10k environments become feasible</li>
 *   <li>Estimated: ~60% faster for viewport queries (12-15ms vs 30-40ms)</li>
 * </ul>
 * <strong>Trade-offs:</strong>
 * <ul>
 *   <li>More complex implementation (chunk management, spatial indexing)</li>
 *   <li>Higher write complexity (multiple BLOBs per tick)</li>
 *   <li>Only worthwhile for very large environments where SingleBlobStrategy becomes bottleneck</li>
 * </ul>
 * 
 * @see IH2EnvStorageStrategy
 * @see AbstractH2EnvStorageStrategy
 */
public class SingleBlobStrategy extends AbstractH2EnvStorageStrategy {
    
    final ICompressionCodec codec;
    String mergeSql;  // SQL string (exposed via getMergeSql() for H2Database caching)
    
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
    public void createTables(Connection conn, int dimensions) throws SQLException {
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
        
        log.debug("Environment tables created for {} dimensions", dimensions);
    }
    
    @Override
    public String getMergeSql() {
        return mergeSql;
    }
    
    @Override
    public void writeTicks(Connection conn, PreparedStatement stmt, List<TickData> ticks, 
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
        
        // Use provided PreparedStatement (cached by H2Database per connection)
        // This eliminates SQL parsing overhead (~30-50% performance improvement)
        for (TickData tick : validTicks) {
            byte[] cellsBlob = serializeTickCells(tick);
            
            stmt.setLong(1, tick.getTickNumber());
            stmt.setBytes(2, cellsBlob);
            stmt.addBatch();
        }
        
        stmt.executeBatch();
        
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

    @Override
    public List<org.evochora.datapipeline.api.contracts.CellState> readTick(Connection conn, long tickNumber, 
                                                                           SpatialRegion region, 
                                                                           EnvironmentProperties envProps) throws SQLException, TickNotFoundException {
        // 1. Read BLOB from database
        PreparedStatement stmt = conn.prepareStatement(
            "SELECT cells_blob FROM environment_ticks WHERE tick_number = ?"
        );
        stmt.setLong(1, tickNumber);
        ResultSet rs = stmt.executeQuery();
        
        if (!rs.next()) {
            throw new TickNotFoundException("Tick " + tickNumber + " not found");
        }
        
        byte[] blobData = rs.getBytes("cells_blob");
        if (blobData == null || blobData.length == 0) {
            return Collections.emptyList();  // Empty tick
        }
        
        // 2. Auto-detect compression
        ICompressionCodec detectedCodec = CompressionCodecFactory.detectFromMagicBytes(blobData);
        
        // 3. Decompress BLOB
        byte[] decompressed;
        try {
            java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(blobData);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream decompressedStream = detectedCodec.wrapInputStream(bis)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = decompressedStream.read(buffer)) != -1) {
                    bos.write(buffer, 0, bytesRead);
                }
                decompressed = bos.toByteArray();
            }
        } catch (IOException e) {
            throw new SQLException("Failed to decompress BLOB for tick " + tickNumber, e);
        }
        
        // 4. Deserialize Protobuf
        CellStateList cellStateList;
        try {
            cellStateList = CellStateList.parseFrom(decompressed);
        } catch (Exception e) {
            throw new SQLException("Failed to deserialize CellStateList for tick " + tickNumber, e);
        }
        
        List<org.evochora.datapipeline.api.contracts.CellState> allCells = cellStateList.getCellsList();
        
        // 5. Filter by region (if provided)
        if (region == null) {
            return allCells;
        }
        
        return filterByRegion(allCells, region, envProps);
    }

    private List<org.evochora.datapipeline.api.contracts.CellState> filterByRegion(
            List<org.evochora.datapipeline.api.contracts.CellState> cells,
            SpatialRegion region,
            EnvironmentProperties envProps) {
        
        int dimensions = envProps.getDimensions();
        
        if (dimensions == 2) {
            return filterByRegion2D(cells, region, envProps);
        } else {
            return filterByRegionND(cells, region, envProps);
        }
    }

    private List<org.evochora.datapipeline.api.contracts.CellState> filterByRegion2D(
            List<org.evochora.datapipeline.api.contracts.CellState> cells,
            SpatialRegion region,
            EnvironmentProperties envProps) {
        
        int[] bounds = region.bounds;
        int xMin = bounds[0], xMax = bounds[1];
        int yMin = bounds[2], yMax = bounds[3];
        
        return cells.stream()
            .filter(cell -> {
                int[] coords = envProps.flatIndexToCoordinates(cell.getFlatIndex());
                int x = coords[0], y = coords[1];
                return x >= xMin && x <= xMax && y >= yMin && y <= yMax;
            })
            .collect(Collectors.toList());
    }

    private List<org.evochora.datapipeline.api.contracts.CellState> filterByRegionND(
            List<org.evochora.datapipeline.api.contracts.CellState> cells,
            SpatialRegion region,
            EnvironmentProperties envProps) {
        
        int[] bounds = region.bounds;
        int dimensions = envProps.getDimensions();
        
        return cells.stream()
            .filter(cell -> {
                int[] coords = envProps.flatIndexToCoordinates(cell.getFlatIndex());
                
                for (int d = 0; d < dimensions; d++) {
                    int min = bounds[d * 2];
                    int max = bounds[d * 2 + 1];
                    if (coords[d] < min || coords[d] > max) {
                        return false;
                    }
                }
                return true;
            })
            .collect(Collectors.toList());
    }
}
