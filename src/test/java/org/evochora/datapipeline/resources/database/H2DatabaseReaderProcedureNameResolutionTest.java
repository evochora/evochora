package org.evochora.datapipeline.resources.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;

import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.ProcFrame;
import org.evochora.datapipeline.api.contracts.ProgramArtifact;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickDetails;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import com.typesafe.config.ConfigFactory;

/**
 * Tests procedure name resolution in H2DatabaseReader.
 * <p>
 * Call frames persist a label hash, not a name. The name is recovered on read from the label map of
 * the program the organism descends from. These tests cover the three cases that arise: a hash the
 * map knows, a hash it does not, and a run whose metadata carries no map at all.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class H2DatabaseReaderProcedureNameResolutionTest {

    private static final String PROGRAM_ID = "prog-1";
    private static final int ORGANISM_ID = 1;
    private static final int TICK = 0;

    private static final int KNOWN_HASH = 4711;
    private static final String KNOWN_NAME = "MAIN_LOOP";
    private static final int UNKNOWN_HASH = 9999;

    @TempDir
    Path tempChunkDir;

    private H2Database database;
    private IDatabaseReaderProvider provider;
    private String runId;

    @BeforeEach
    void setUp() {
        String dbUrl = "jdbc:h2:mem:test-procname-resolution-" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        com.typesafe.config.Config dbConfig = ConfigFactory.parseString(
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
    void resolvesNameOfAKnownLabelHash() throws Exception {
        setupDatabase(true);
        writeOrganismWithCallStack(KNOWN_HASH);

        try (IDatabaseReader reader = provider.createReader(runId)) {
            OrganismTickDetails details = reader.readOrganismDetails(TICK, ORGANISM_ID);
            assertThat(details.state.callStack).hasSize(1);
            assertThat(details.state.callStack.get(0).procName).isEqualTo(KNOWN_NAME);
        }
    }

    /**
     * An organism inherits the program ID of its ancestor while its code mutates, so a call may
     * target a hash the original program never held. Reporting no name is the truthful result, and
     * a placeholder would be wrong: callers tell named from unnamed frames by emptiness.
     */
    @Test
    void yieldsEmptyNameForAnUnknownLabelHash() throws Exception {
        setupDatabase(true);
        writeOrganismWithCallStack(UNKNOWN_HASH);

        try (IDatabaseReader reader = provider.createReader(runId)) {
            OrganismTickDetails details = reader.readOrganismDetails(TICK, ORGANISM_ID);
            assertThat(details.state.callStack).hasSize(1);
            assertThat(details.state.callStack.get(0).procName).isEmpty();
        }
    }

    /** A run whose metadata holds no label map resolves every frame to an empty name. */
    @Test
    void yieldsEmptyNamesWhenTheRunHasNoLabelMap() throws Exception {
        setupDatabase(false);
        writeOrganismWithCallStack(KNOWN_HASH);

        try (IDatabaseReader reader = provider.createReader(runId)) {
            OrganismTickDetails details = reader.readOrganismDetails(TICK, ORGANISM_ID);
            assertThat(details.state.callStack).hasSize(1);
            assertThat(details.state.callStack.get(0).procName).isEmpty();
        }
    }

    private void setupDatabase(boolean withLabelMap) throws Exception {
        Object connObj = database.acquireDedicatedConnection();
        try (Connection conn = (Connection) connObj) {
            String schemaName = "SIM_" + runId.toUpperCase().replaceAll("[^A-Z0-9_]", "_");
            conn.createStatement().execute("CREATE SCHEMA IF NOT EXISTS \"" + schemaName + "\"");
            conn.createStatement().execute("SET SCHEMA \"" + schemaName + "\"");

            conn.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS metadata (\"key\" VARCHAR PRIMARY KEY, \"value\" TEXT)");

            ProgramArtifact.Builder program = ProgramArtifact.newBuilder().setProgramId(PROGRAM_ID);
            if (withLabelMap) {
                program.putLabelValueToName(KNOWN_HASH, KNOWN_NAME);
            }

            SimulationMetadata metadata = SimulationMetadata.newBuilder()
                    .setSimulationRunId(runId)
                    .setResolvedConfigJson(TestMetadataHelper.builder()
                        .shape(10, 10)
                        .toroidal(false)
                        .samplingInterval(1)
                        .build())
                    .setStartTimeMs(System.currentTimeMillis())
                    .setInitialSeed(42L)
                    .addPrograms(program.build())
                    .build();
            String metadataJson =
                    org.evochora.datapipeline.utils.protobuf.ProtobufConverter.toJson(metadata);
            conn.createStatement().execute("INSERT INTO metadata (\"key\", \"value\") VALUES ('full_metadata', '"
                    + metadataJson.replace("'", "''") + "')");

            database.doCreateOrganismTables(conn);
            conn.commit();
        }
    }

    private void writeOrganismWithCallStack(int labelHash) throws Exception {
        Object connObj = database.acquireDedicatedConnection();
        try (Connection conn = (Connection) connObj) {
            org.evochora.datapipeline.utils.H2SchemaUtil.setSchema(conn, runId);

            ProcFrame frame = ProcFrame.newBuilder()
                    .setLabelHash(labelHash)
                    .setAbsoluteReturnIp(Vector.newBuilder().addComponents(3).addComponents(4).build())
                    .build();

            OrganismState organism = OrganismState.newBuilder()
                    .setOrganismId(ORGANISM_ID)
                    .setBirthTick(0)
                    .setProgramId(PROGRAM_ID)
                    .setInitialPosition(Vector.newBuilder().addComponents(0).addComponents(0).build())
                    .setEnergy(100)
                    .setIp(Vector.newBuilder().addComponents(1).addComponents(2).build())
                    .setDv(Vector.newBuilder().addComponents(0).addComponents(1).build())
                    .addDataPointers(Vector.newBuilder().addComponents(5).addComponents(5).build())
                    .setActiveDpIndex(0)
                    .addCallStack(frame)
                    .build();

            TickData tick = TickData.newBuilder()
                    .setTickNumber(TICK)
                    .setSimulationRunId(runId)
                    .addOrganisms(organism)
                    .build();

            database.doWriteOrganismTick(conn, tick);
            database.doCommitOrganismWrites(conn);
        }
    }
}
