package org.evochora.datapipeline.resources.database.h2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.database.TickNotFoundException;
import org.evochora.datapipeline.utils.H2SchemaUtil;
import org.evochora.datapipeline.utils.compression.CompressionCodecFactory;
import org.evochora.datapipeline.utils.compression.ICompressionCodec;

import com.typesafe.config.Config;

/**
 * RowPerChunkStrategy: Stores entire TickDataChunks as BLOBs (one row per chunk).
 * <p>
 * This strategy stores delta-compressed chunks directly in the database without
 * decompression, maximizing storage savings. Decompression is deferred to the
 * EnvironmentController, which can cache decompressed chunks for efficient
 * sequential access.
 * <p>
 * <strong>Storage:</strong> One row per chunk
 * <ul>
 *   <li>100 ticks per chunk = 100× fewer rows than per-tick storage</li>
 *   <li>15M ticks = ~150K rows (vs 15M rows with per-tick)</li>
 *   <li>Chunk BLOB: ~5-15MB compressed (snapshot + 99 deltas)</li>
 * </ul>
 * <p>
 * <strong>Schema:</strong>
 * <pre>
 * CREATE TABLE environment_chunks (
 *   first_tick BIGINT PRIMARY KEY,
 *   last_tick BIGINT NOT NULL,
 *   chunk_blob BYTEA NOT NULL
 * )
 * </pre>
 * <p>
 * <strong>Query Performance:</strong>
 * <ul>
 *   <li>Write: Fast (1 MERGE per chunk)</li>
 *   <li>Read: Must load entire chunk (~10-20ms), then decompress specific tick</li>
 *   <li>With LRU cache in controller: subsequent ticks in same chunk are instant</li>
 * </ul>
 * <p>
 * <strong>Best For:</strong> Production deployments with delta compression enabled.
 * 
 * @see IH2EnvStorageStrategy
 * @see AbstractH2EnvStorageStrategy
 * @see org.evochora.datapipeline.utils.delta.DeltaCodec
 */
public class RowPerChunkStrategy extends AbstractH2EnvStorageStrategy {
    
    private final ICompressionCodec codec;
    private String mergeSql;
    
    /**
     * Creates RowPerChunkStrategy with optional compression for the BLOB storage.
     * <p>
     * Note: This compression is for the outer BLOB storage layer. The TickDataChunk
     * itself may already contain compressed delta data internally.
     * 
     * @param options Config with optional compression block
     */
    public RowPerChunkStrategy(Config options) {
        super(options);
        this.codec = CompressionCodecFactory.create(options);
        log.debug("RowPerChunkStrategy initialized with compression: {}", codec.getName());
    }
    
    @Override
    public void createTables(Connection conn, int dimensions) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // Create environment_chunks table
            // first_tick is PRIMARY KEY (automatic B-tree index)
            // last_tick indexed for range queries
            H2SchemaUtil.executeDdlIfNotExists(
                stmt,
                "CREATE TABLE IF NOT EXISTS environment_chunks (" +
                "  first_tick BIGINT PRIMARY KEY," +
                "  last_tick BIGINT NOT NULL," +
                "  chunk_blob BYTEA NOT NULL" +
                ")",
                "environment_chunks"
            );
            
            // Index on last_tick for efficient range queries
            H2SchemaUtil.executeDdlIfNotExists(
                stmt,
                "CREATE INDEX IF NOT EXISTS idx_env_chunks_last_tick ON environment_chunks(last_tick)",
                "idx_env_chunks_last_tick"
            );
        }
        
        // Cache SQL string for MERGE operations
        this.mergeSql = "MERGE INTO environment_chunks (first_tick, last_tick, chunk_blob) " +
                       "KEY (first_tick) VALUES (?, ?, ?)";
        
        log.debug("Environment chunk tables created for {} dimensions", dimensions);
    }
    
    @Override
    public String getMergeSql() {
        return mergeSql;
    }
    
    @Override
    public void writeChunks(Connection conn, List<TickDataChunk> chunks) throws SQLException {
        if (chunks.isEmpty()) {
            return;
        }
        
        try (PreparedStatement stmt = conn.prepareStatement(mergeSql)) {
            for (TickDataChunk chunk : chunks) {
                // Validate chunk has required fields
                if (!chunk.hasSnapshot()) {
                    log.warn("Chunk starting at tick {} has no snapshot - skipping", 
                             chunk.getSnapshot().getTickNumber());
                    continue;
                }
                
                long firstTick = chunk.getSnapshot().getTickNumber();
                long lastTick = calculateLastTick(chunk);
                byte[] chunkBlob = serializeChunk(chunk);
                
                stmt.setLong(1, firstTick);
                stmt.setLong(2, lastTick);
                stmt.setBytes(3, chunkBlob);
                stmt.addBatch();
            }
            
            stmt.executeBatch();
            log.debug("Wrote {} chunks to environment_chunks table", chunks.size());
        }
    }
    
    /**
     * Calculates the last tick number in the chunk.
     * <p>
     * The last tick is either:
     * <ul>
     *   <li>The tick number of the last delta (if deltas exist)</li>
     *   <li>The snapshot tick number (if no deltas)</li>
     * </ul>
     */
    private long calculateLastTick(TickDataChunk chunk) {
        int deltaCount = chunk.getDeltasCount();
        if (deltaCount > 0) {
            return chunk.getDeltas(deltaCount - 1).getTickNumber();
        }
        return chunk.getSnapshot().getTickNumber();
    }
    
    /**
     * Serializes the chunk to a compressed BLOB.
     * <p>
     * Uses the configured compression codec to wrap the Protobuf serialization.
     */
    private byte[] serializeChunk(TickDataChunk chunk) throws SQLException {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (OutputStream compressed = codec.wrapOutputStream(baos)) {
                chunk.writeTo(compressed);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new SQLException("Failed to serialize chunk starting at tick: " + 
                                   chunk.getSnapshot().getTickNumber(), e);
        }
    }

    @Override
    public TickDataChunk readChunkContaining(Connection conn, long tickNumber) 
            throws SQLException, TickNotFoundException {
        
        // Query: find chunk where first_tick <= tickNumber AND last_tick >= tickNumber
        String sql = "SELECT chunk_blob FROM environment_chunks " +
                    "WHERE first_tick <= ? AND last_tick >= ? " +
                    "LIMIT 1";
        
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, tickNumber);
            stmt.setLong(2, tickNumber);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new TickNotFoundException("No chunk found containing tick " + tickNumber);
                }
                
                byte[] blobData = rs.getBytes("chunk_blob");
                if (blobData == null || blobData.length == 0) {
                    throw new TickNotFoundException("Chunk for tick " + tickNumber + " has empty BLOB");
                }
                
                return deserializeChunk(blobData, tickNumber);
            }
        }
    }
    
    /**
     * Deserializes a chunk from compressed BLOB data.
     * <p>
     * Auto-detects compression format from magic bytes.
     */
    private TickDataChunk deserializeChunk(byte[] blobData, long tickNumber) throws SQLException {
        try {
            // Auto-detect compression
            ICompressionCodec detectedCodec = CompressionCodecFactory.detectFromMagicBytes(blobData);
            
            // Decompress
            java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(blobData);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream decompressedStream = detectedCodec.wrapInputStream(bis)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = decompressedStream.read(buffer)) != -1) {
                    bos.write(buffer, 0, bytesRead);
                }
            }
            
            // Parse Protobuf
            return TickDataChunk.parseFrom(bos.toByteArray());
            
        } catch (IOException e) {
            throw new SQLException("Failed to deserialize chunk containing tick " + tickNumber, e);
        }
    }
}
