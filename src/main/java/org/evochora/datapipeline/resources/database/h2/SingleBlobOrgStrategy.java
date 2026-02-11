package org.evochora.datapipeline.resources.database.h2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.OrganismStateList;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickSummary;
import org.evochora.datapipeline.api.resources.database.dto.TickRange;
import org.evochora.datapipeline.utils.H2SchemaUtil;
import org.evochora.datapipeline.utils.compression.CompressionCodecFactory;
import org.evochora.datapipeline.utils.compression.ICompressionCodec;

import com.typesafe.config.Config;

/**
 * SingleBlobOrgStrategy: Stores all organisms of a tick in a single BLOB.
 * <p>
 * <strong>Storage:</strong> ~10-50 KB per tick (with compression)
 * <ul>
 *   <li>100 organisms × 500 bytes (protobuf) = 50 KB raw</li>
 *   <li>With compression: ~10-20 KB per tick</li>
 *   <li>200k ticks = 2-4 GB total ✅</li>
 * </ul>
 * <p>
 * <strong>Comparison with Row-per-Organism:</strong>
 * <ul>
 *   <li>Row-per-organism: 100 organisms × 200k ticks = 20 MILLION rows</li>
 *   <li>BLOB strategy: 200k ticks = 200k rows (~100× fewer!)</li>
 * </ul>
 * <p>
 * <strong>Query Performance:</strong> Must deserialize entire tick for single organism lookup.
 * <p>
 * <strong>Write Performance:</strong> Excellent (one row per tick, single MERGE).
 * <p>
 * <strong>Best For:</strong> Large runs where row count causes H2 MERGE slowdown.
 * 
 * @see IH2OrgStorageStrategy
 * @see AbstractH2OrgStorageStrategy
 */
public class SingleBlobOrgStrategy extends AbstractH2OrgStorageStrategy {
    
    private String organismsMergeSql;
    private String statesMergeSql;
    
    /**
     * Creates SingleBlobOrgStrategy with optional compression.
     * 
     * @param options Config with optional compression block
     */
    public SingleBlobOrgStrategy(Config options) {
        super(options);
    }
    
    @Override
    public void createTables(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // Static organism metadata table (always row-per-organism)
            H2SchemaUtil.executeDdlIfNotExists(
                stmt,
                "CREATE TABLE IF NOT EXISTS organisms (" +
                "  organism_id INT PRIMARY KEY," +
                "  parent_id INT NULL," +
                "  birth_tick BIGINT NOT NULL," +
                "  program_id TEXT NOT NULL," +
                "  initial_position BYTEA NOT NULL," +
                "  genome_hash BIGINT DEFAULT 0" +
                ")",
                "organisms"
            );
            
            // Per-tick organism states table (BLOB strategy: one row per tick)
            // Note: Table name changed from organism_states to organism_ticks
            H2SchemaUtil.executeDdlIfNotExists(
                stmt,
                "CREATE TABLE IF NOT EXISTS organism_ticks (" +
                "  tick_number BIGINT PRIMARY KEY," +
                "  organisms_blob BYTEA NOT NULL" +
                ")",
                "organism_ticks"
            );
            // No additional index needed - tick_number is PRIMARY KEY
        }
        
        // Cache SQL strings
        this.organismsMergeSql = "MERGE INTO organisms (" +
                "organism_id, parent_id, birth_tick, program_id, initial_position, genome_hash" +
                ") KEY (organism_id) VALUES (?, ?, ?, ?, ?, ?)";
        
        this.statesMergeSql = "MERGE INTO organism_ticks (tick_number, organisms_blob) " +
                "KEY (tick_number) VALUES (?, ?)";
        
        log.debug("Organism tables created with BLOB strategy");
    }
    
    @Override
    public String getOrganismsMergeSql() {
        return organismsMergeSql;
    }
    
    @Override
    public String getStatesMergeSql() {
        return statesMergeSql;
    }
    
    @Override
    public void writeOrganisms(Connection conn, PreparedStatement stmt, List<TickData> ticks) 
            throws SQLException {
        // Extract unique organisms from all ticks and write static metadata
        // Use a set to track which organisms we've already added to the batch
        java.util.Set<Integer> seenOrganisms = new java.util.HashSet<>();
        
        for (TickData tick : ticks) {
            for (OrganismState org : tick.getOrganismsList()) {
                int organismId = org.getOrganismId();
                if (seenOrganisms.contains(organismId)) {
                    continue;  // Already in batch
                }
                seenOrganisms.add(organismId);

                stmt.setInt(1, organismId);
                if (org.hasParentId()) {
                    stmt.setInt(2, org.getParentId());
                } else {
                    stmt.setNull(2, java.sql.Types.INTEGER);
                }
                stmt.setLong(3, org.getBirthTick());
                stmt.setString(4, org.getProgramId());
                stmt.setBytes(5, org.getInitialPosition().toByteArray());
                stmt.setLong(6, org.getGenomeHash());
                stmt.addBatch();
            }
            Thread.yield();
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
        
        int writtenCount = 0;
        for (TickData tick : ticks) {
            if (tick.getOrganismsList().isEmpty()) {
                log.debug("Tick {} has no organisms - skipping organism_ticks write", tick.getTickNumber());
                continue;
            }

            byte[] blob = serializeOrganisms(tick);
            stmt.setLong(1, tick.getTickNumber());
            stmt.setBytes(2, blob);
            stmt.addBatch();
            writtenCount++;
            Thread.yield();
        }
        
        if (writtenCount > 0) {
            stmt.executeBatch();
            log.debug("Wrote {} ticks to organism_ticks table (BLOB strategy)", writtenCount);
        }
    }
    
    /**
     * Serializes all organisms of a tick to compressed BLOB.
     */
    private byte[] serializeOrganisms(TickData tick) throws SQLException {
        try {
            OrganismStateList orgList = OrganismStateList.newBuilder()
                .addAllOrganisms(tick.getOrganismsList())
                .build();
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (OutputStream compressed = codec.wrapOutputStream(baos)) {
                orgList.writeTo(compressed);
            }
            
            return baos.toByteArray();
            
        } catch (IOException e) {
            throw new SQLException("Failed to serialize organisms for tick: " + tick.getTickNumber(), e);
        }
    }
    
    @Override
    public List<OrganismTickSummary> readOrganismsAtTick(Connection conn, long tickNumber) 
            throws SQLException {
        // 1. Read BLOB from database
        List<OrganismState> organisms = readOrganismsBlobForTick(conn, tickNumber);
        if (organisms.isEmpty()) {
            return new ArrayList<>();
        }
        
        // 2. Read static info from organisms table (for parent_id, birth_tick)
        java.util.Map<Integer, StaticInfo> staticInfoMap = readAllStaticInfo(conn);
        
        // 3. Convert to DTOs
        List<OrganismTickSummary> result = new ArrayList<>(organisms.size());
        for (OrganismState org : organisms) {
            int organismId = org.getOrganismId();
            StaticInfo staticInfo = staticInfoMap.get(organismId);
            
            Integer parentId = staticInfo != null ? staticInfo.parentId :
                    (org.hasParentId() ? org.getParentId() : null);
            long birthTick = staticInfo != null ? staticInfo.birthTick : org.getBirthTick();
            long genomeHash = staticInfo != null ? staticInfo.genomeHash : org.getGenomeHash();

            result.add(new OrganismTickSummary(
                organismId,
                org.getEnergy(),
                vectorToArray(org.getIp()),
                vectorToArray(org.getDv()),
                dataPointersToArray(org),
                org.getActiveDpIndex(),
                parentId,
                birthTick,
                org.getEntropyRegister(),
                genomeHash
            ));
        }
        
        return result;
    }
    
    @Override
    public OrganismState readSingleOrganismState(Connection conn, long tickNumber, int organismId) 
            throws SQLException {
        // Read the entire BLOB and filter for the specific organism
        // This is less efficient than row-per-organism for single lookups,
        // but much better for large-scale write performance
        
        List<OrganismState> organisms = readOrganismsBlobForTick(conn, tickNumber);
        
        // Find the specific organism
        for (OrganismState org : organisms) {
            if (org.getOrganismId() == organismId) {
                return org;
            }
        }
        
        return null;  // Not found
    }
    
    @Override
    public TickRange getAvailableTickRange(Connection conn) throws SQLException {
        String sql = "SELECT MIN(tick_number) as min_tick, MAX(tick_number) as max_tick FROM organism_ticks";
        
        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            
            if (!rs.next()) {
                return null;
            }
            
            long minTick = rs.getLong("min_tick");
            if (rs.wasNull()) {
                return null;  // Table is empty
            }
            long maxTick = rs.getLong("max_tick");
            
            return new TickRange(minTick, maxTick);
        }
    }
    
    // ==================== Helper Methods ====================
    
    /**
     * Reads and deserializes the organisms BLOB for a specific tick.
     */
    private List<OrganismState> readOrganismsBlobForTick(Connection conn, long tickNumber) 
            throws SQLException {
        String sql = "SELECT organisms_blob FROM organism_ticks WHERE tick_number = ?";
        
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, tickNumber);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return new ArrayList<>();  // No data for this tick
                }
                
                byte[] blobData = rs.getBytes("organisms_blob");
                if (blobData == null || blobData.length == 0) {
                    return new ArrayList<>();
                }
                
                // Auto-detect compression
                ICompressionCodec detectedCodec = CompressionCodecFactory.detectFromMagicBytes(blobData);
                
                // Decompress and deserialize
                try (ByteArrayInputStream bis = new ByteArrayInputStream(blobData);
                     InputStream decompressed = detectedCodec.wrapInputStream(bis)) {
                    
                    OrganismStateList orgList = OrganismStateList.parseFrom(decompressed);
                    return orgList.getOrganismsList();
                    
                } catch (IOException e) {
                    throw new SQLException("Failed to decompress/deserialize organisms for tick " + tickNumber, e);
                }
            }
        }
    }
    
    /**
     * Reads static organism info (parent_id, birth_tick, genome_hash) for all organisms.
     */
    private java.util.Map<Integer, StaticInfo> readAllStaticInfo(Connection conn) throws SQLException {
        String sql = "SELECT organism_id, parent_id, birth_tick, genome_hash FROM organisms";

        java.util.Map<Integer, StaticInfo> result = new java.util.HashMap<>();

        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                int organismId = rs.getInt("organism_id");
                int parentIdRaw = rs.getInt("parent_id");
                Integer parentId = rs.wasNull() ? null : parentIdRaw;
                long birthTick = rs.getLong("birth_tick");
                long genomeHash = rs.getLong("genome_hash");

                result.put(organismId, new StaticInfo(parentId, birthTick, genomeHash));
            }
        }

        return result;
    }
    
    /**
     * Helper record for static organism info.
     */
    private record StaticInfo(Integer parentId, long birthTick, long genomeHash) {}
    
    /**
     * Converts a Protobuf Vector to int[].
     */
    private static int[] vectorToArray(Vector v) {
        if (v == null) {
            return new int[0];
        }
        int[] result = new int[v.getComponentsCount()];
        for (int i = 0; i < result.length; i++) {
            result[i] = v.getComponents(i);
        }
        return result;
    }
    
    /**
     * Converts organism data pointers to int[][].
     */
    private static int[][] dataPointersToArray(OrganismState org) {
        int count = org.getDataPointersCount();
        int[][] result = new int[count][];
        for (int i = 0; i < count; i++) {
            result[i] = vectorToArray(org.getDataPointers(i));
        }
        return result;
    }
}
