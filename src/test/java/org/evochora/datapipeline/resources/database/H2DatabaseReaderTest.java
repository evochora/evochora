package org.evochora.datapipeline.resources.database;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.*;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickDetails;
import org.evochora.datapipeline.api.resources.database.dto.TickRange;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.datapipeline.resources.database.h2.SingleBlobStrategy;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

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
            "  className = \"org.evochora.datapipeline.resources.database.h2.SingleBlobStrategy\"\n" +
            "  options { compression { enabled = false } }\n" +
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
        // Given: Create schema and write ticks
        Object connObj = database.acquireDedicatedConnection();
        try (Connection conn = (Connection) connObj) {
            // Set schema
            conn.createStatement().execute("CREATE SCHEMA IF NOT EXISTS \"SIM_" + runId.toUpperCase().replaceAll("[^A-Z0-9_]", "_") + "\"");
            conn.createStatement().execute("SET SCHEMA \"SIM_" + runId.toUpperCase().replaceAll("[^A-Z0-9_]", "_") + "\"");

            // Create tables using strategy
            SingleBlobStrategy strategy = new SingleBlobStrategy(ConfigFactory.empty());
            strategy.createTables(conn, 2);

            // Write ticks 10, 20, 30
            List<TickData> ticks = List.of(
                TickData.newBuilder()
                    .setTickNumber(10L)
                    .setSimulationRunId(runId)
                    .addCells(CellState.newBuilder().setFlatIndex(0).setOwnerId(100).setMoleculeType(1).setMoleculeValue(50).build())
                    .build(),
                TickData.newBuilder()
                    .setTickNumber(20L)
                    .setSimulationRunId(runId)
                    .addCells(CellState.newBuilder().setFlatIndex(0).setOwnerId(101).setMoleculeType(1).setMoleculeValue(60).build())
                    .build(),
                TickData.newBuilder()
                    .setTickNumber(30L)
                    .setSimulationRunId(runId)
                    .addCells(CellState.newBuilder().setFlatIndex(0).setOwnerId(102).setMoleculeType(1).setMoleculeValue(70).build())
                    .build()
            );

            var envProps = new org.evochora.runtime.model.EnvironmentProperties(new int[]{10, 10}, false);
            var stmt = conn.prepareStatement(strategy.getMergeSql());
            strategy.writeTicks(conn, stmt, ticks, envProps);
            stmt.close();
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
        // Given: Create schema but no ticks
        Object connObj = database.acquireDedicatedConnection();
        try (Connection conn = (Connection) connObj) {
            conn.createStatement().execute("CREATE SCHEMA IF NOT EXISTS \"SIM_" + runId.toUpperCase().replaceAll("[^A-Z0-9_]", "_") + "\"");
            conn.createStatement().execute("SET SCHEMA \"SIM_" + runId.toUpperCase().replaceAll("[^A-Z0-9_]", "_") + "\"");

            SingleBlobStrategy strategy = new SingleBlobStrategy(ConfigFactory.empty());
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
        // Given: Create schema but no environment_ticks table
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
        // Given: Create schema and write single tick
        Object connObj = database.acquireDedicatedConnection();
        try (Connection conn = (Connection) connObj) {
            conn.createStatement().execute("CREATE SCHEMA IF NOT EXISTS \"SIM_" + runId.toUpperCase().replaceAll("[^A-Z0-9_]", "_") + "\"");
            conn.createStatement().execute("SET SCHEMA \"SIM_" + runId.toUpperCase().replaceAll("[^A-Z0-9_]", "_") + "\"");

            SingleBlobStrategy strategy = new SingleBlobStrategy(ConfigFactory.empty());
            strategy.createTables(conn, 2);

            // Write single tick
            List<TickData> ticks = List.of(
                TickData.newBuilder()
                    .setTickNumber(42L)
                    .setSimulationRunId(runId)
                    .addCells(CellState.newBuilder().setFlatIndex(0).setOwnerId(100).setMoleculeType(1).setMoleculeValue(50).build())
                    .build()
            );

            var envProps = new org.evochora.runtime.model.EnvironmentProperties(new int[]{10, 10}, false);
            var stmt = conn.prepareStatement(strategy.getMergeSql());
            strategy.writeTicks(conn, stmt, ticks, envProps);
            stmt.close();
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
                    .setEnvironment(EnvironmentConfig.newBuilder()
                            .setDimensions(2)
                            .addShape(10)
                            .addToroidal(false)
                            .addShape(10)
                            .addToroidal(false)
                            .build())
                    .setStartTimeMs(System.currentTimeMillis())
                    .setInitialSeed(42L)
                    .setSamplingInterval(1)
                    .build();
            String metadataJson = org.evochora.datapipeline.utils.protobuf.ProtobufConverter.toJson(metadata);
            conn.createStatement().execute("INSERT INTO metadata (\"key\", \"value\") VALUES ('full_metadata', '" +
                    metadataJson.replace("'", "''") + "')");

            // Create organism tables
            SingleBlobStrategy strategy = new SingleBlobStrategy(ConfigFactory.empty());
            database.doCreateOrganismTables(conn);

            // Write organism with instruction data
            Vector ipBeforeFetch = Vector.newBuilder().addComponents(1).addComponents(2).build();
            Vector dvBeforeFetch = Vector.newBuilder().addComponents(0).addComponents(1).build();
            int setiOpcode = Instruction.getInstructionIdByName("SETI") | org.evochora.runtime.Config.TYPE_CODE;
            int regArg = new Molecule(org.evochora.runtime.Config.TYPE_DATA, 0).toInt();
            int immArg = new Molecule(org.evochora.runtime.Config.TYPE_DATA, 42).toInt();

            OrganismState orgState = OrganismState.newBuilder()
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
                    .setDvBeforeFetch(dvBeforeFetch)
                    .build();

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
}

