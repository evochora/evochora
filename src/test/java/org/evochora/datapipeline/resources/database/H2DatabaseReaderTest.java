package org.evochora.datapipeline.resources.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.RegisterValue;
import org.evochora.test.utils.ProtoTestUtils;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.datapipeline.api.resources.database.dto.ChunkIndexSummary;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickDetails;
import org.evochora.datapipeline.api.resources.database.dto.SampledTickRange;
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
 * Tests for H2DatabaseReader.
 * <p>
 * Covers what the reader answers about a run:
 * <ul>
 *   <li>The recorded tick ranges and the state of the chunk index they come from</li>
 *   <li>Organism details with resolved instructions, and the genome ancestor walk</li>
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
    void getTickRanges_reportsTheStretchesTheRunRecorded() throws Exception {
        // Two chunks continue each other at a step of 10, a third starts again after a gap
        writeChunkIndex(chunkOf(10L, 30L, 3), chunkOf(40L, 60L, 3), chunkOf(200L, 220L, 3));

        try (IDatabaseReader reader = provider.createReader(runId)) {
            assertThat(reader.getTickRanges().ranges()).containsExactly(
                new SampledTickRange(10L, 60L, 10L),
                new SampledTickRange(200L, 220L, 10L));
        }
    }

    @Test
    void getTickRanges_isEmptyWhenTableNotExists() throws Exception {
        // Given: Create schema but no environment_chunks table
        Object connObj = database.acquireDedicatedConnection();
        try (Connection conn = (Connection) connObj) {
            conn.createStatement().execute("CREATE SCHEMA IF NOT EXISTS \"" + schemaName() + "\"");
        }

        try (IDatabaseReader reader = provider.createReader(runId)) {
            assertThat(reader.getTickRanges().ranges()).isEmpty();
            assertThat(reader.getChunkIndexSummary()).isEqualTo(new ChunkIndexSummary(0L, 0L, 0L));
        }
    }

    @Test
    void getChunkIndexSummary_countsTheChunksAndHowFarTheyReach() throws Exception {
        writeChunkIndex(chunkOf(10L, 30L, 3), chunkOf(40L, 60L, 3));

        try (IDatabaseReader reader = provider.createReader(runId)) {
            assertThat(reader.getChunkIndexSummary()).isEqualTo(new ChunkIndexSummary(2L, 60L, 6L));
        }
    }

    /**
     * The bounds and sample count of one chunk, as the index records them.
     */
    private record IndexedChunk(long firstTick, long lastTick, int tickCount) {}

    private static IndexedChunk chunkOf(long firstTick, long lastTick, int tickCount) {
        return new IndexedChunk(firstTick, lastTick, tickCount);
    }

    private String schemaName() {
        return "SIM_" + runId.toUpperCase().replaceAll("[^A-Z0-9_]", "_");
    }

    /**
     * Indexes the given chunks for the run, writing each chunk's file along with its index row.
     */
    private void writeChunkIndex(IndexedChunk... chunks) throws Exception {
        Object connObj = database.acquireDedicatedConnection();
        try (Connection conn = (Connection) connObj) {
            conn.createStatement().execute("CREATE SCHEMA IF NOT EXISTS \"" + schemaName() + "\"");
            conn.createStatement().execute("SET SCHEMA \"" + schemaName() + "\"");

            RowPerChunkStrategy strategy = new RowPerChunkStrategy(ConfigFactory.parseString(
                    "chunkDirectory = \"" + tempChunkDir.toString().replace("\\", "/") + "\""));
            strategy.createTables(conn, 2);

            for (IndexedChunk c : chunks) {
                TickDataChunk chunk = TickDataChunk.newBuilder()
                    .setSimulationRunId(runId)
                    .setFirstTick(c.firstTick())
                    .setLastTick(c.lastTick())
                    .setTickCount(c.tickCount())
                    .setSnapshot(TickData.newBuilder()
                        .setTickNumber(c.firstTick())
                        .setSimulationRunId(runId)
                        .build())
                    .build();
                strategy.writeRawChunk(conn, c.firstTick(), c.lastTick(), c.tickCount(), chunk.toByteArray());
            }
            strategy.commitRawChunks(conn);
            conn.commit();
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
                    .addAllRegisters(ProtoTestUtils.buildFlatRegisters(new int[]{42}, null, null, null))
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

            database.doWriteOrganismTick(conn, tick, java.util.Map.of());
            database.doCommitOrganismWrites(conn);
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

    // --- readGenomeAncestors tests ---

    /**
     * Inserts an organism, taking the parent's genome from the parent's row the way the indexer
     * takes it from the parent at birth. Without a parent the column stays NULL.
     */
    private void insertOrganism(Connection conn, int organismId, Integer parentId,
                                long birthTick, long genomeHash) throws Exception {
        String parentGenomeSql = parentId != null
            ? "(SELECT genome_hash FROM organisms WHERE organism_id = " + parentId + ")"
            : "NULL";
        insertOrganismWithParentGenome(conn, organismId, parentId, birthTick, genomeHash, parentGenomeSql);
    }

    /**
     * Inserts an organism with the parent's genome stated outright, for the cases where the
     * parent's own row is not there to take it from.
     */
    private void insertOrganism(Connection conn, int organismId, Integer parentId,
                                long birthTick, long genomeHash, long parentGenomeHash) throws Exception {
        insertOrganismWithParentGenome(conn, organismId, parentId, birthTick, genomeHash,
            String.valueOf(parentGenomeHash));
    }

    private void insertOrganismWithParentGenome(Connection conn, int organismId, Integer parentId,
                                                long birthTick, long genomeHash, String parentGenomeSql)
            throws Exception {
        String parentSql = parentId != null ? String.valueOf(parentId) : "NULL";
        conn.createStatement().execute(
            "INSERT INTO organisms (organism_id, parent_id, birth_tick, program_id, initial_position, "
            + "genome_hash, generation, parent_genome_hash) VALUES ("
            + organismId + ", " + parentSql + ", " + birthTick + ", 'prog', X'0000', "
            + genomeHash + ", 0, " + parentGenomeSql + ")");
    }

    /**
     * Creates schema and organism tables for ancestor tests.
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
    void readGenomeAncestors_walksTheChainOfTheRequestedGenome() throws Exception {
        try (Connection conn = setupOrganismSchema()) {
            insertOrganism(conn, 1, null, 0, 1000L);  // root
            insertOrganism(conn, 2, 1, 10, 2000L);    // child of 1, new genome
            insertOrganism(conn, 3, 2, 20, 3000L);    // grandchild, new genome
            conn.commit();
        }

        try (IDatabaseReader reader = provider.createReader(runId)) {
            Map<Long, Long> ancestors = reader.readGenomeAncestors(List.of(3000L));

            assertThat(ancestors).hasSize(3);
            assertThat(ancestors.get(3000L)).isEqualTo(2000L);
            assertThat(ancestors.get(2000L)).isEqualTo(1000L);
            assertThat(ancestors.get(1000L)).isNull();
        }
    }

    @Test
    void readGenomeAncestors_carrierWithoutParentIsARoot() throws Exception {
        try (Connection conn = setupOrganismSchema()) {
            insertOrganism(conn, 1, null, 0, 1000L);
            insertOrganism(conn, 2, null, 0, 2000L);
            conn.commit();
        }

        try (IDatabaseReader reader = provider.createReader(runId)) {
            Map<Long, Long> ancestors = reader.readGenomeAncestors(List.of(1000L, 2000L));

            assertThat(ancestors).hasSize(2);
            assertThat(ancestors).containsKey(1000L).containsKey(2000L);
            assertThat(ancestors.get(1000L)).isNull();
            assertThat(ancestors.get(2000L)).isNull();
        }
    }

    @Test
    void readGenomeAncestors_parentCarryingGenomeZeroIsARoot() throws Exception {
        try (Connection conn = setupOrganismSchema()) {
            insertOrganism(conn, 1, null, 0, 0L);      // parent without a genome
            insertOrganism(conn, 2, 1, 10, 2000L);
            conn.commit();
        }

        try (IDatabaseReader reader = provider.createReader(runId)) {
            Map<Long, Long> ancestors = reader.readGenomeAncestors(List.of(2000L));

            assertThat(ancestors).hasSize(1);
            assertThat(ancestors.get(2000L)).isNull();
        }
    }

    @Test
    void readGenomeAncestors_missingParentRowDoesNotBreakTheChain() throws Exception {
        // While a run is still being indexed the parent's row can be absent. The child carries the
        // genome its parent had, recorded at birth, so the chain continues past the gap - it names
        // the ancestor genome even though no row for that ancestor exists yet.
        try (Connection conn = setupOrganismSchema()) {
            insertOrganism(conn, 2, 1, 10, 2000L, 1000L);   // parent 1 is not indexed
            conn.commit();
        }

        try (IDatabaseReader reader = provider.createReader(runId)) {
            Map<Long, Long> ancestors = reader.readGenomeAncestors(List.of(2000L));

            assertThat(ancestors).containsEntry(2000L, 1000L);
            assertThat(ancestors).doesNotContainKey(1000L);   // no carrier of it in the run yet
        }
    }

    @Test
    void readGenomeAncestors_ignoresGenomeHashZero() throws Exception {
        try (Connection conn = setupOrganismSchema()) {
            insertOrganism(conn, 1, null, 0, 0L);
            insertOrganism(conn, 2, null, 0, 1000L);
            conn.commit();
        }

        try (IDatabaseReader reader = provider.createReader(runId)) {
            Map<Long, Long> ancestors = reader.readGenomeAncestors(List.of(0L, 1000L));

            assertThat(ancestors).hasSize(1);
            assertThat(ancestors).containsKey(1000L).doesNotContainKey(0L);
        }
    }

    @Test
    void readGenomeAncestors_omitsGenomesThatDoNotOccur() throws Exception {
        try (Connection conn = setupOrganismSchema()) {
            insertOrganism(conn, 1, null, 0, 1000L);
            conn.commit();
        }

        try (IDatabaseReader reader = provider.createReader(runId)) {
            Map<Long, Long> ancestors = reader.readGenomeAncestors(List.of(1000L, 9999L));

            assertThat(ancestors).hasSize(1);
            assertThat(ancestors).containsKey(1000L).doesNotContainKey(9999L);
        }
    }

    @Test
    void readGenomeAncestors_skipsCarriersThatInheritedTheirGenomeUnchanged() throws Exception {
        // Filtering precedes ordering: organism 2 carries genome 1000 unchanged and must not be
        // chosen as its first carrier, which would make genome 1000 its own ancestor.
        try (Connection conn = setupOrganismSchema()) {
            insertOrganism(conn, 1, null, 0, 1000L);
            insertOrganism(conn, 2, 1, 10, 1000L);     // same genome as its parent
            insertOrganism(conn, 3, 2, 20, 2000L);
            conn.commit();
        }

        try (IDatabaseReader reader = provider.createReader(runId)) {
            Map<Long, Long> ancestors = reader.readGenomeAncestors(List.of(2000L));

            assertThat(ancestors).hasSize(2);
            assertThat(ancestors.get(2000L)).isEqualTo(1000L);
            assertThat(ancestors.get(1000L)).isNull();
        }
    }

    @Test
    void readGenomeAncestors_firstCarrierWinsWhenAGenomeAppearsTwice() throws Exception {
        // Two organisms independently arrive at the same genome hash from different parents.
        try (Connection conn = setupOrganismSchema()) {
            insertOrganism(conn, 1, null, 0, 1000L);
            insertOrganism(conn, 2, null, 0, 2000L);
            insertOrganism(conn, 3, 1, 10, 3000L);     // from genome 1000
            insertOrganism(conn, 4, 2, 10, 3000L);     // same genome, from genome 2000
            conn.commit();
        }

        try (IDatabaseReader reader = provider.createReader(runId)) {
            Map<Long, Long> ancestors = reader.readGenomeAncestors(List.of(3000L));

            assertThat(ancestors.get(3000L)).isEqualTo(1000L);
        }
    }

    @Test
    void readGenomeAncestors_doesNotDependOnTheTickRequested() throws Exception {
        // The first carrier of a visible genome was born no later than the tick it is visible at,
        // so the answer is the same whether later organisms exist or not.
        try (Connection conn = setupOrganismSchema()) {
            insertOrganism(conn, 1, null, 0, 1000L);
            insertOrganism(conn, 2, 1, 50, 2000L);
            conn.commit();
        }

        Map<Long, Long> before;
        try (IDatabaseReader reader = provider.createReader(runId)) {
            before = reader.readGenomeAncestors(List.of(2000L));
        }

        try (Connection conn = setupOrganismSchema()) {
            insertOrganism(conn, 3, 2, 100, 3000L);    // a later genome joins the run
            conn.commit();
        }

        try (IDatabaseReader reader = provider.createReader(runId)) {
            assertThat(reader.readGenomeAncestors(List.of(2000L))).isEqualTo(before);
        }
    }

    @Test
    void readGenomeAncestors_sharedAncestorsAppearOnceEach() throws Exception {
        try (Connection conn = setupOrganismSchema()) {
            insertOrganism(conn, 1, null, 0, 1000L);
            insertOrganism(conn, 2, 1, 10, 2000L);     // both descend from genome 1000
            insertOrganism(conn, 3, 1, 10, 3000L);
            conn.commit();
        }

        try (IDatabaseReader reader = provider.createReader(runId)) {
            Map<Long, Long> ancestors = reader.readGenomeAncestors(List.of(2000L, 3000L, 2000L));

            assertThat(ancestors).hasSize(3);
            assertThat(ancestors.get(2000L)).isEqualTo(1000L);
            assertThat(ancestors.get(3000L)).isEqualTo(1000L);
            assertThat(ancestors.get(1000L)).isNull();
        }
    }

    @Test
    void readGenomeAncestors_terminatesOnCyclicData() throws Exception {
        // Organism ids make a cycle impossible in data the pipeline writes, because a parent is
        // always created before its child. The walk must terminate even if that does not hold.
        try (Connection conn = setupOrganismSchema()) {
            // Stated outright: neither parent row exists yet when its child is written
            insertOrganism(conn, 1, 2, 0, 1000L, 2000L);
            insertOrganism(conn, 2, 1, 0, 2000L, 1000L);
            conn.commit();
        }

        try (IDatabaseReader reader = provider.createReader(runId)) {
            Map<Long, Long> ancestors = reader.readGenomeAncestors(List.of(1000L));

            assertThat(ancestors).hasSize(2);
            assertThat(ancestors.get(1000L)).isEqualTo(2000L);
            assertThat(ancestors.get(2000L)).isEqualTo(1000L);
        }
    }
}
