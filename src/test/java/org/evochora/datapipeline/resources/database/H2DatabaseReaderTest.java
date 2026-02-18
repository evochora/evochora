package org.evochora.datapipeline.resources.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.evochora.datapipeline.CellStateTestHelper;
import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.RegisterValue;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickDetails;
import org.evochora.datapipeline.api.resources.database.dto.TickRange;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.resources.database.h2.RowPerChunkStrategy;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for H2DatabaseReader.getTickRange() method.
 * <p>
 * Tests tick range queries:
 * <ul>
 *   <li>Successful query with ticks</li>
 *   <li>Null when no ticks available</li>
 *   <li>Correct min/max calculation</li>
 * </ul>
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class H2DatabaseReaderTest {

    @TempDir
    Path tempChunkDir;

    private H2Database database;
    private IDatabaseReaderProvider provider;
    private String runId;

    @BeforeAll
    static void initInstructionSet() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        String dbUrl = "jdbc:h2:mem:test-reader-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        Config dbConfig = ConfigFactory.parseString(
            "jdbcUrl = \"" + dbUrl + "\"\n" +
            "username = \"sa\"\n" +
            "password = \"\"\n" +
            "maxPoolSize = 5\n" +
            "h2EnvironmentStrategy {\n" +
            "  className = \"org.evochora.datapipeline.resources.database.h2.RowPerChunkStrategy\"\n" +
            "  options { chunkDirectory = \"" + tempChunkDir.toString().replace("\\", "/") + "\" }\n" +
            "}\n"
        );
        database = new H2Database("test-db", dbConfig);
        provider = database;
        runId = "test-run-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void getTickRange_returnsCorrectRange() throws Exception {
        // Given: Create schema and write chunks
        Object connObj = database.acquireDedicatedConnection();
        try (Connection conn = (Connection) connObj) {
            // Set schema
            conn.createStatement().execute("CREATE SCHEMA IF NOT EXISTS \"SIM_" + runId.toUpperCase().replaceAll("[^A-Z0-9_]", "_") + "\"");
            conn.createStatement().execute("SET SCHEMA \"SIM_" + runId.toUpperCase().replaceAll("[^A-Z0-9_]", "_") + "\"");

            // Create tables using strategy
            RowPerChunkStrategy strategy = new RowPerChunkStrategy(ConfigFactory.parseString(
                    "chunkDirectory = \"" + tempChunkDir.toString().replace("\\", "/") + "\""));
            strategy.createTables(conn, 2);

            // Write chunk spanning ticks 10-30
            TickData snapshot = TickData.newBuilder()
                .setTickNumber(10L)
                .setSimulationRunId(runId)
                .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                    CellStateTestHelper.createCellStateBuilder(0, 100, 1, 50, 0).build()
                )))
                .build();
            
            TickDataChunk chunk = TickDataChunk.newBuilder()
                .setSnapshot(snapshot)
                .addDeltas(org.evochora.datapipeline.api.contracts.TickDelta.newBuilder().setTickNumber(20L).build())
                .addDeltas(org.evochora.datapipeline.api.contracts.TickDelta.newBuilder().setTickNumber(30L).build())
                .build();

            strategy.writeChunks(conn, List.of(chunk));
            conn.commit();
        }

        // When: Query tick range
        try (IDatabaseReader reader = provider.createReader(runId)) {
            TickRange range = reader.getTickRange();

            // Then: Should return correct range
            assertThat(range).isNotNull();
            assertThat(range.minTick()).isEqualTo(10L);
            assertThat(range.maxTick()).isEqualTo(30L);
        }
    }

    @Test
    void getTickRange_returnsNullWhenNoTicks() throws Exception {
        // Given: Create schema but no chunks
        Object connObj = database.acquireDedicatedConnection();
        try (Connection conn = (Connection) connObj) {
            conn.createStatement().execute("CREATE SCHEMA IF NOT EXISTS \"SIM_" + runId.toUpperCase().replaceAll("[^A-Z0-9_]", "_") + "\"");
            conn.createStatement().execute("SET SCHEMA \"SIM_" + runId.toUpperCase().replaceAll("[^A-Z0-9_]", "_") + "\"");

            RowPerChunkStrategy strategy = new RowPerChunkStrategy(ConfigFactory.parseString(
                    "chunkDirectory = \"" + tempChunkDir.toString().replace("\\", "/") + "\""));
            strategy.createTables(conn, 2);
        }

        // When: Query tick range
        try (IDatabaseReader reader = provider.createReader(runId)) {
            TickRange range = reader.getTickRange();

            // Then: Should return null
            assertThat(range).isNull();
        }
    }

    @Test
    void getTickRange_returnsNullWhenTableNotExists() throws Exception {
        // Given: Create schema but no environment_chunks table
        Object connObj = database.acquireDedicatedConnection();
        try (Connection conn = (Connection) connObj) {
            conn.createStatement().execute("CREATE SCHEMA IF NOT EXISTS \"SIM_" + runId.toUpperCase().replaceAll("[^A-Z0-9_]", "_") + "\"");
        }

        // When: Query tick range
        try (IDatabaseReader reader = provider.createReader(runId)) {
            TickRange range = reader.getTickRange();

            // Then: Should return null (table doesn't exist)
            assertThat(range).isNull();
        }
    }

    @Test
    void getTickRange_handlesSingleTick() throws Exception {
        // Given: Create schema and write single chunk with single tick
        Object connObj = database.acquireDedicatedConnection();
        try (Connection conn = (Connection) connObj) {
            conn.createStatement().execute("CREATE SCHEMA IF NOT EXISTS \"SIM_" + runId.toUpperCase().replaceAll("[^A-Z0-9_]", "_") + "\"");
            conn.createStatement().execute("SET SCHEMA \"SIM_" + runId.toUpperCase().replaceAll("[^A-Z0-9_]", "_") + "\"");

            RowPerChunkStrategy strategy = new RowPerChunkStrategy(ConfigFactory.parseString(
                    "chunkDirectory = \"" + tempChunkDir.toString().replace("\\", "/") + "\""));
            strategy.createTables(conn, 2);

            // Write single chunk with single tick (no deltas)
            TickData snapshot = TickData.newBuilder()
                .setTickNumber(42L)
                .setSimulationRunId(runId)
                .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                    CellStateTestHelper.createCellStateBuilder(0, 100, 1, 50, 0).build()
                )))
                .build();
            
            TickDataChunk chunk = TickDataChunk.newBuilder()
                .setSnapshot(snapshot)
                .build();

            strategy.writeChunks(conn, List.of(chunk));
            conn.commit();
        }

        // When: Query tick range
        try (IDatabaseReader reader = provider.createReader(runId)) {
            TickRange range = reader.getTickRange();

            // Then: minTick and maxTick should be the same
            assertThat(range).isNotNull();
            assertThat(range.minTick()).isEqualTo(42L);
            assertThat(range.maxTick()).isEqualTo(42L);
        }
    }

    @Test
    void readOrganismDetails_withInstructionData_resolvesInstructions() throws Exception {
        // Given: Create schema, metadata, and write organism with instruction data
        Object connObj = database.acquireDedicatedConnection();
        try (Connection conn = (Connection) connObj) {
            String schemaName = "SIM_" + runId.toUpperCase().replaceAll("[^A-Z0-9_]", "_");
            conn.createStatement().execute("CREATE SCHEMA IF NOT EXISTS \"" + schemaName + "\"");
            conn.createStatement().execute("SET SCHEMA \"" + schemaName + "\"");

            // Create metadata table and insert metadata
            conn.createStatement().execute("CREATE TABLE IF NOT EXISTS metadata (\"key\" VARCHAR PRIMARY KEY, \"value\" TEXT)");
            SimulationMetadata metadata = SimulationMetadata.newBuilder()
                    .setSimulationRunId(runId)
                    .setResolvedConfigJson(TestMetadataHelper.builder()
                        .shape(10, 10)
                        .toroidal(false)
                        .samplingInterval(1)
                        .build())
                    .setStartTimeMs(System.currentTimeMillis())
                    .setInitialSeed(42L)
                    .build();
            String metadataJson = org.evochora.datapipeline.utils.protobuf.ProtobufConverter.toJson(metadata);
            conn.createStatement().execute("INSERT INTO metadata (\"key\", \"value\") VALUES ('full_metadata', '" +
                    metadataJson.replace("'", "''") + "')");

            // Create organism tables
            database.doCreateOrganismTables(conn);

            // Write organism with instruction data
            Vector ipBeforeFetch = Vector.newBuilder().addComponents(1).addComponents(2).build();
            Vector dvBeforeFetch = Vector.newBuilder().addComponents(0).addComponents(1).build();
            int setiOpcode = Instruction.getInstructionIdByName("SETI") | org.evochora.runtime.Config.TYPE_CODE;
            int regArg = new Molecule(org.evochora.runtime.Config.TYPE_DATA, 0).toInt();
            int immArg = new Molecule(org.evochora.runtime.Config.TYPE_DATA, 42).toInt();

            OrganismState.Builder orgBuilder = OrganismState.newBuilder()
                    .setOrganismId(1)
                    .setBirthTick(0)
                    .setProgramId("prog-1")
                    .setInitialPosition(Vector.newBuilder().addComponents(0).addComponents(0).build())
                    .setEnergy(100)
                    .setIp(Vector.newBuilder().addComponents(1).addComponents(2).build())
                    .setDv(Vector.newBuilder().addComponents(0).addComponents(1).build())
                    .addDataPointers(Vector.newBuilder().addComponents(5).addComponents(5).build())
                    .setActiveDpIndex(0)
                    .addDataRegisters(RegisterValue.newBuilder().setScalar(42).build())
                    .setInstructionOpcodeId(setiOpcode)
                    .addInstructionRawArguments(regArg)
                    .addInstructionRawArguments(immArg)
                    .setInstructionEnergyCost(5)
                    .setIpBeforeFetch(ipBeforeFetch)
                    .setDvBeforeFetch(dvBeforeFetch);
            
            // Add register values before execution (required for annotation display)
            // SETI %DR0, DATA:10 - first argument is REGISTER (registerId=0)
            orgBuilder.putInstructionRegisterValuesBefore(0, RegisterValue.newBuilder().setScalar(42).build());
            
            OrganismState orgState = orgBuilder.build();

            TickData tick = TickData.newBuilder()
                    .setTickNumber(1L)
                    .setSimulationRunId(runId)
                    .addOrganisms(orgState)
                    .build();

            database.doWriteOrganismStates(conn, java.util.List.of(tick));
            conn.commit();
        }

        // When: Read organism details
        try (IDatabaseReader reader = provider.createReader(runId)) {
            OrganismTickDetails details = reader.readOrganismDetails(1L, 1);

            // Then: Instructions should be resolved
            assertThat(details).isNotNull();
            assertThat(details.state.instructions).isNotNull();
            assertThat(details.state.instructions.last).isNotNull();
            assertThat(details.state.instructions.last.opcodeName).isEqualTo("SETI");
            assertThat(details.state.instructions.last.arguments).hasSize(2);
            assertThat(details.state.instructions.last.arguments.get(0).type).isEqualTo("REGISTER");
            assertThat(details.state.instructions.last.arguments.get(1).type).isEqualTo("IMMEDIATE");
            assertThat(details.state.instructions.last.energyCost).isEqualTo(5);
        }
    }

    // --- readGenomeLineageTree tests ---

    /**
     * Inserts a row into the organisms table with minimal required fields.
     */
    private void insertOrganism(Connection conn, int organismId, Integer parentId,
                                long birthTick, long genomeHash) throws Exception {
        String parentSql = parentId != null ? String.valueOf(parentId) : "NULL";
        conn.createStatement().execute(
            "INSERT INTO organisms (organism_id, parent_id, birth_tick, program_id, initial_position, genome_hash) " +
            "VALUES (" + organismId + ", " + parentSql + ", " + birthTick + ", 'prog', X'0000', " + genomeHash + ")");
    }

    /**
     * Creates schema and organism tables for lineage tree tests.
     */
    private Connection setupOrganismSchema() throws Exception {
        Connection conn = (Connection) database.acquireDedicatedConnection();
        String schemaName = "SIM_" + runId.toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        conn.createStatement().execute("CREATE SCHEMA IF NOT EXISTS \"" + schemaName + "\"");
        conn.createStatement().execute("SET SCHEMA \"" + schemaName + "\"");
        database.doCreateOrganismTables(conn);
        return conn;
    }

    @Test
    void readGenomeLineageTree_basicParentChildRelationships() throws Exception {
        try (Connection conn = setupOrganismSchema()) {
            insertOrganism(conn, 1, null, 0, 1000L);  // root
            insertOrganism(conn, 2, 1, 10, 2000L);    // child of 1, new genome
            insertOrganism(conn, 3, 2, 20, 3000L);    // grandchild, new genome
            conn.commit();
        }

        try (IDatabaseReader reader = provider.createReader(runId)) {
            Map<Long, Long> tree = reader.readGenomeLineageTree(100);

            assertThat(tree).hasSize(3);
            assertThat(tree.get(1000L)).isNull();          // root → null
            assertThat(tree.get(2000L)).isEqualTo(1000L);  // child → parent genome
            assertThat(tree.get(3000L)).isEqualTo(2000L);  // grandchild → parent genome
        }
    }

    @Test
    void readGenomeLineageTree_rootGenomesMapToNull() throws Exception {
        try (Connection conn = setupOrganismSchema()) {
            insertOrganism(conn, 1, null, 0, 1000L);  // primordial, no parent
            insertOrganism(conn, 2, null, 0, 2000L);  // primordial, no parent
            conn.commit();
        }

        try (IDatabaseReader reader = provider.createReader(runId)) {
            Map<Long, Long> tree = reader.readGenomeLineageTree(100);

            assertThat(tree).hasSize(2);
            assertThat(tree.get(1000L)).isNull();
            assertThat(tree.get(2000L)).isNull();
        }
    }

    @Test
    void readGenomeLineageTree_excludesGenomeHashZero() throws Exception {
        try (Connection conn = setupOrganismSchema()) {
            insertOrganism(conn, 1, null, 0, 0L);     // genome=0, should be excluded
            insertOrganism(conn, 2, null, 0, 1000L);   // normal genome
            conn.commit();
        }

        try (IDatabaseReader reader = provider.createReader(runId)) {
            Map<Long, Long> tree = reader.readGenomeLineageTree(100);

            assertThat(tree).hasSize(1);
            assertThat(tree).containsKey(1000L);
            assertThat(tree).doesNotContainKey(0L);
        }
    }

    @Test
    void readGenomeLineageTree_filtersSelfReferencingGenomes() throws Exception {
        // Org 2 inherits same genome as parent (no mutation) — should not create a self-referencing entry
        try (Connection conn = setupOrganismSchema()) {
            insertOrganism(conn, 1, null, 0, 1000L);   // root
            insertOrganism(conn, 2, 1, 10, 1000L);     // same genome as parent (no mutation)
            insertOrganism(conn, 3, 2, 20, 2000L);     // new genome, parent is org 2 (genome 1000)
            conn.commit();
        }

        try (IDatabaseReader reader = provider.createReader(runId)) {
            Map<Long, Long> tree = reader.readGenomeLineageTree(100);

            assertThat(tree).hasSize(2);
            assertThat(tree.get(1000L)).isNull();          // root (org 1 provides this)
            assertThat(tree.get(2000L)).isEqualTo(1000L);  // derived from genome 1000
        }
    }

    @Test
    void readGenomeLineageTree_firstOccurrenceWinsForDuplicateGenomes() throws Exception {
        // Two organisms independently mutate to the same genome hash (collision).
        // The first by organism_id should determine the parent.
        try (Connection conn = setupOrganismSchema()) {
            insertOrganism(conn, 1, null, 0, 1000L);   // root A
            insertOrganism(conn, 2, null, 0, 2000L);   // root B
            insertOrganism(conn, 3, 1, 10, 3000L);     // derived from A
            insertOrganism(conn, 4, 2, 10, 3000L);     // same genome 3000, but from B
            conn.commit();
        }

        try (IDatabaseReader reader = provider.createReader(runId)) {
            Map<Long, Long> tree = reader.readGenomeLineageTree(100);

            assertThat(tree.get(3000L)).isEqualTo(1000L);  // org 3 (lower ID) wins → parent is 1000
        }
    }

    @Test
    void readGenomeLineageTree_respectsBirthTickFilter() throws Exception {
        try (Connection conn = setupOrganismSchema()) {
            insertOrganism(conn, 1, null, 0, 1000L);    // born at tick 0
            insertOrganism(conn, 2, 1, 50, 2000L);      // born at tick 50
            insertOrganism(conn, 3, 2, 100, 3000L);     // born at tick 100
            conn.commit();
        }

        try (IDatabaseReader reader = provider.createReader(runId)) {
            // Query at tick 60 — should only include organisms born ≤ 60
            Map<Long, Long> tree = reader.readGenomeLineageTree(60);

            assertThat(tree).hasSize(2);
            assertThat(tree).containsKey(1000L);
            assertThat(tree).containsKey(2000L);
            assertThat(tree).doesNotContainKey(3000L);
        }
    }

    @Test
    void readGenomeLineageTree_parentWithGenomeZeroMapsToNull() throws Exception {
        // Parent exists but has genome_hash=0 — child should be treated as root
        try (Connection conn = setupOrganismSchema()) {
            insertOrganism(conn, 1, null, 0, 0L);      // parent with genome=0
            insertOrganism(conn, 2, 1, 10, 2000L);     // child — parent genome is 0
            conn.commit();
        }

        try (IDatabaseReader reader = provider.createReader(runId)) {
            Map<Long, Long> tree = reader.readGenomeLineageTree(100);

            assertThat(tree).hasSize(1);
            assertThat(tree.get(2000L)).isNull();  // treated as root (parent genome=0)
        }
    }
}

