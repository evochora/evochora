package org.evochora.datapipeline.resources.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
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
import org.evochora.datapipeline.api.resources.database.dto.ParentRows;
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
 *   <li>Organism details with resolved instructions, and the parent relation read in pages</li>
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
        writeChunkIndex(chunkOf(10L, 30L, 3, 10), chunkOf(40L, 60L, 3, 10), chunkOf(200L, 220L, 3, 10));

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
        writeChunkIndex(chunkOf(10L, 30L, 3, 10), chunkOf(40L, 60L, 3, 10));

        try (IDatabaseReader reader = provider.createReader(runId)) {
            assertThat(reader.getChunkIndexSummary()).isEqualTo(new ChunkIndexSummary(2L, 60L, 6L));
        }
    }

    /**
     * The bounds, tick count and step of one chunk, as the index records them.
     */
    private record IndexedChunk(long firstTick, long lastTick, int tickCount, int step) {}

    private static IndexedChunk chunkOf(long firstTick, long lastTick, int tickCount, int step) {
        return new IndexedChunk(firstTick, lastTick, tickCount, step);
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
                    .setSamplingInterval(c.step())
                    .setSnapshot(TickData.newBuilder()
                        .setTickNumber(c.firstTick())
                        .setSimulationRunId(runId)
                        .build())
                    .build();
                strategy.writeRawChunk(conn, c.firstTick(), c.lastTick(), c.tickCount(), c.step(),
                        chunk.toByteArray());
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

    // --- readParents tests ---

    /**
     * Inserts an organism row with the given parent, NULL for a founder.
     */
    private void insertOrganism(Connection conn, int organismId, Integer parentId) throws Exception {
        String parentSql = parentId != null ? String.valueOf(parentId) : "NULL";
        conn.createStatement().execute(
            "INSERT INTO organisms (organism_id, parent_id, birth_tick, program_id, initial_position) "
            + "VALUES (" + organismId + ", " + parentSql + ", 0, 'prog', X'0000')");
    }

    /**
     * Creates schema and organism tables for the parent relation tests.
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
    void readParents_pagesInIdOrderAndReportsAFounderAsParentZero() throws Exception {
        try (Connection conn = setupOrganismSchema()) {
            // Inserted out of order, with a hole at 4 as a row that is not indexed yet leaves it
            insertOrganism(conn, 5, 2);
            insertOrganism(conn, 1, null);
            insertOrganism(conn, 3, 1);
            insertOrganism(conn, 2, 1);
            conn.commit();
        }

        try (IDatabaseReader reader = provider.createReader(runId)) {
            ParentRows first = reader.readParents(0, 3);
            assertThat(first.ids()).containsExactly(1, 2, 3);
            assertThat(first.parents()).containsExactly(0, 1, 1);

            ParentRows second = reader.readParents(3, 3);
            assertThat(second.ids()).containsExactly(5);
            assertThat(second.parents()).containsExactly(2);

            assertThat(reader.readParents(5, 3).size()).isZero();
        }
    }
}
