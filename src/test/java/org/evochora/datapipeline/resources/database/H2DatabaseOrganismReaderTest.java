package org.evochora.datapipeline.resources.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.ProcFrame;
import org.evochora.datapipeline.api.contracts.RegisterValue;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.OrganismNotFoundException;
import org.evochora.datapipeline.api.resources.database.dto.OrganismRuntimeView;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickDetails;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickSummary;
import org.evochora.datapipeline.api.resources.database.dto.ProcFrameView;
import org.evochora.datapipeline.api.resources.database.dto.RegisterValueView;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import com.typesafe.config.ConfigFactory;

/**
 * Integration tests for organism read API in H2DatabaseReader.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class H2DatabaseOrganismReaderTest {

    @TempDir
    Path tempDir;

    private H2Database database;

    @BeforeEach
    void setUp() {
        String dbPath = tempDir.toString().replace("\\", "/");
        var config = ConfigFactory.parseString("""
            jdbcUrl = "jdbc:h2:file:%s/test-organism-reader;MODE=PostgreSQL"
            """.formatted(dbPath));

        database = new H2Database("test-db", config);
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void readOrganismsAtTick_returnsSummariesForExistingTick() throws Exception {
        TickData tick = TickData.newBuilder()
                .setTickNumber(1L)
                .addOrganisms(buildOrganismState(1))
                .addOrganisms(buildOrganismState(2))
                .build();

        try (Connection conn = getConnectionWithSchema("run-reader-1")) {
            database.doCreateOrganismTables(conn);
            database.doWriteOrganismTick(conn, tick);
            database.doCommitOrganismWrites(conn);
        }

        try (IDatabaseReader reader = database.createReader("run-reader-1")) {
            List<OrganismTickSummary> organisms = reader.readOrganismsAtTick(1L);

            assertThat(organisms).hasSize(2);
            assertThat(organisms)
                    .extracting(o -> o.organismId)
                    .containsExactly(1, 2);

            OrganismTickSummary first = organisms.get(0);
            assertThat(first.energy).isEqualTo(42);
            assertThat(first.ip).containsExactly(1);
            assertThat(first.dv).containsExactly(0, 1);
            assertThat(first.dataPointers.length).isEqualTo(1);
            assertThat(first.dataPointers[0]).containsExactly(5);
            assertThat(first.activeDpIndex).isEqualTo(0);
        }
    }

    @Test
    void readOrganismsAtTick_returnsEmptyListForTickWithoutOrganisms() throws Exception {
        TickData tick = TickData.newBuilder()
                .setTickNumber(1L)
                .addOrganisms(buildOrganismState(1))
                .build();

        try (Connection conn = getConnectionWithSchema("run-reader-2")) {
            database.doCreateOrganismTables(conn);
            database.doWriteOrganismTick(conn, tick);
            database.doCommitOrganismWrites(conn);
        }

        try (IDatabaseReader reader = database.createReader("run-reader-2")) {
            List<OrganismTickSummary> organisms = reader.readOrganismsAtTick(2L);
            assertThat(organisms).isEmpty();
        }
    }

    @Test
    void readOrganismDetails_roundTripStaticAndRuntimeState() throws Exception {
        int organismId = 3;
        TickData tick = TickData.newBuilder()
                .setTickNumber(5L)
                .addOrganisms(buildOrganismState(organismId))
                .build();

        try (Connection conn = getConnectionWithSchema("run-reader-3")) {
            // Create metadata table and insert metadata (required for instruction resolution)
            conn.createStatement().execute("CREATE TABLE IF NOT EXISTS metadata (\"key\" VARCHAR PRIMARY KEY, \"value\" TEXT)");
            SimulationMetadata metadata = SimulationMetadata.newBuilder()
                    .setSimulationRunId("run-reader-3")
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

            database.doCreateOrganismTables(conn);
            database.doWriteOrganismTick(conn, tick);
            database.doCommitOrganismWrites(conn);
            conn.commit();
        }

        try (IDatabaseReader reader = database.createReader("run-reader-3")) {
            OrganismTickDetails details = reader.readOrganismDetails(5L, organismId);

            assertThat(details.organismId).isEqualTo(organismId);
            assertThat(details.tick).isEqualTo(5L);

            // Static info
            assertThat(details.staticInfo.programId).isEqualTo("prog-" + organismId);
            assertThat(details.staticInfo.birthTick).isEqualTo(0L);
            assertThat(details.staticInfo.initialPosition).containsExactly(0, 0);

            // Runtime view: hot path
            OrganismRuntimeView state = details.state;
            assertThat(state.energy).isEqualTo(42);
            assertThat(state.ip).containsExactly(1);
            assertThat(state.dv).containsExactly(0, 1);
            assertThat(state.dataPointers.length).isEqualTo(1);
            assertThat(state.dataPointers[0]).containsExactly(5);
            assertThat(state.activeDpIndex).isEqualTo(0);

            // Runtime view: cold path (blob)
            assertThat(state.dataRegisters).hasSize(1);
            RegisterValueView drv = state.dataRegisters.get(0);
            assertThat(drv.kind).isEqualTo(RegisterValueView.Kind.MOLECULE);
            // buildOrganismState uses scalar=7, which corresponds to a molecule with value 7
            Molecule mol = Molecule.fromInt(7);
            assertThat(drv.raw).isEqualTo(7);
            assertThat(drv.typeId).isEqualTo(mol.type());
            assertThat(drv.type).isEqualTo(org.evochora.runtime.model.MoleculeTypeRegistry.typeToName(mol.type()));
            assertThat(drv.value).isEqualTo(mol.toScalarValue());

            assertThat(state.callStack).hasSize(1);
            ProcFrameView frame = state.callStack.get(0);
            assertThat(frame.procName).isEqualTo("main");
            assertThat(frame.absoluteReturnIp).containsExactly(10);

            assertThat(state.instructionFailed).isTrue();
            assertThat(state.failureReason).isEqualTo("test-failure");
            assertThat(state.failureCallStack).hasSize(1);
        }
    }

    @Test
    void readOrganismDetails_throwsWhenNoStateExists() throws Exception {
        try (Connection conn = getConnectionWithSchema("run-reader-4")) {
            database.doCreateOrganismTables(conn);
            // No writes for this run
        }

        try (IDatabaseReader reader = database.createReader("run-reader-4")) {
            assertThrows(OrganismNotFoundException.class, () ->
                    reader.readOrganismDetails(0L, 1));
        }
    }

    private Connection getConnectionWithSchema(String runId) throws SQLException {
        try {
            java.lang.reflect.Field dataSourceField = H2Database.class.getDeclaredField("dataSource");
            dataSourceField.setAccessible(true);
            @SuppressWarnings("resource")
            com.zaxxer.hikari.HikariDataSource dataSource =
                    (com.zaxxer.hikari.HikariDataSource) dataSourceField.get(database);

            Connection conn = dataSource.getConnection();
            org.evochora.datapipeline.utils.H2SchemaUtil.setupRunSchema(conn, runId,
                    (c, schemaName) -> { /* no-op, tables created by doCreateOrganismTables */ });
            org.evochora.datapipeline.utils.H2SchemaUtil.setSchema(conn, runId);
            return conn;
        } catch (ReflectiveOperationException e) {
            throw new SQLException("Failed to access H2 dataSource", e);
        }
    }

    private OrganismState buildOrganismState(int id) {
        Vector ip = Vector.newBuilder().addComponents(1).build();
        Vector dv = Vector.newBuilder().addComponents(0).addComponents(1).build();

        return OrganismState.newBuilder()
                .setOrganismId(id)
                .setBirthTick(0)
                .setProgramId("prog-" + id)
                .setInitialPosition(Vector.newBuilder().addComponents(0).addComponents(0).build())
                .setEnergy(42)
                .setIp(ip)
                .setDv(dv)
                .addDataPointers(Vector.newBuilder().addComponents(5).build())
                .setActiveDpIndex(0)
                .addDataRegisters(RegisterValue.newBuilder().setScalar(7).build())
                .addLocationRegisters(Vector.newBuilder().addComponents(2).addComponents(3).build())
                .addDataStack(RegisterValue.newBuilder().setScalar(9).build())
                .addLocationStack(Vector.newBuilder().addComponents(4).build())
                .addCallStack(ProcFrame.newBuilder()
                        .setProcName("main")
                        .setAbsoluteReturnIp(Vector.newBuilder().addComponents(10).build())
                        .build())
                .setInstructionFailed(true)
                .setFailureReason("test-failure")
                .addFailureCallStack(ProcFrame.newBuilder()
                        .setProcName("fail")
                        .setAbsoluteReturnIp(Vector.newBuilder().addComponents(11).build())
                        .build())
                .build();
    }
}


