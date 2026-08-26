package org.evochora.datapipeline.resources.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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
import org.evochora.datapipeline.api.resources.database.TickNotFoundException;
import org.evochora.datapipeline.api.resources.database.dto.OrganismRuntimeView;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickDetails;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickSummary;
import org.evochora.datapipeline.api.resources.database.dto.ProcFrameView;
import org.evochora.datapipeline.api.resources.database.dto.RegisterValueView;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.test.utils.ProtoTestUtils;
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
    void readTotalOrganismsCreated_returnsTheValueTheTickReported() throws Exception {
        TickData tick = TickData.newBuilder()
                .setTickNumber(1L)
                .addOrganisms(buildOrganismState(1))
                .setTotalOrganismsCreated(4711L)
                .build();

        try (Connection conn = getConnectionWithSchema("run-total-1")) {
            database.doCreateOrganismTables(conn);
            database.doWriteOrganismTick(conn, tick);
            database.doCommitOrganismWrites(conn);
        }

        try (IDatabaseReader reader = database.createReader("run-total-1")) {
            assertThat(reader.readTotalOrganismsCreated(1L)).isEqualTo(4711);
        }
    }

    @Test
    void readTotalOrganismsCreated_storesATotalForATickWithoutOrganisms() throws Exception {
        TickData extinction = TickData.newBuilder()
                .setTickNumber(2L)
                .setTotalOrganismsCreated(1234L)
                .build();

        try (Connection conn = getConnectionWithSchema("run-total-2")) {
            database.doCreateOrganismTables(conn);
            database.doWriteOrganismTick(conn, extinction);
            database.doCommitOrganismWrites(conn);
        }

        try (IDatabaseReader reader = database.createReader("run-total-2")) {
            assertThat(reader.readOrganismsAtTick(2L)).isEmpty();
            assertThat(reader.readTotalOrganismsCreated(2L)).isEqualTo(1234);
        }
    }

    @Test
    void readTotalOrganismsCreated_survivesAReprocessedTick() throws Exception {
        TickData tick = TickData.newBuilder()
                .setTickNumber(3L)
                .addOrganisms(buildOrganismState(1))
                .setTotalOrganismsCreated(99L)
                .build();

        try (Connection conn = getConnectionWithSchema("run-total-3")) {
            database.doCreateOrganismTables(conn);
            database.doWriteOrganismTick(conn, tick);
            database.doCommitOrganismWrites(conn);
            // At-least-once delivery: the same chunk can arrive again
            database.doWriteOrganismTick(conn, tick);
            database.doCommitOrganismWrites(conn);
        }

        try (IDatabaseReader reader = database.createReader("run-total-3")) {
            assertThat(reader.readTotalOrganismsCreated(3L)).isEqualTo(99);
        }
    }

    @Test
    void readTotalOrganismsCreated_failsRatherThanTruncatingAnOversizedTotal() throws Exception {
        // The API returns int because organism ids are INT, so a run cannot exceed that range
        // without overflowing the ids themselves. Should a stored total ever exceed it anyway,
        // the contract promises a failure rather than a wrong number.
        TickData tick = TickData.newBuilder()
                .setTickNumber(1L)
                .addOrganisms(buildOrganismState(1))
                .setTotalOrganismsCreated(1L)
                .build();

        try (Connection conn = getConnectionWithSchema("run-total-overflow")) {
            database.doCreateOrganismTables(conn);
            database.doWriteOrganismTick(conn, tick);
            database.doCommitOrganismWrites(conn);

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("UPDATE organism_tick_stats SET total_organisms_created = "
                        + (Integer.MAX_VALUE + 1L) + " WHERE tick_number = 1");
            }
            conn.commit();
        }

        try (IDatabaseReader reader = database.createReader("run-total-overflow")) {
            assertThatThrownBy(() -> reader.readTotalOrganismsCreated(1L))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void readTotalOrganismsCreated_throwsForATickThatWasNeverIndexed() throws Exception {
        TickData tick = TickData.newBuilder()
                .setTickNumber(1L)
                .addOrganisms(buildOrganismState(1))
                .setTotalOrganismsCreated(7L)
                .build();

        try (Connection conn = getConnectionWithSchema("run-total-4")) {
            database.doCreateOrganismTables(conn);
            database.doWriteOrganismTick(conn, tick);
            database.doCommitOrganismWrites(conn);
        }

        try (IDatabaseReader reader = database.createReader("run-total-4")) {
            assertThatThrownBy(() -> reader.readTotalOrganismsCreated(2L))
                    .isInstanceOf(TickNotFoundException.class)
                    .hasMessageContaining("2");
        }
    }

    @Test
    void readOrganismsAtTick_takesStaticFieldsFromTheStoredState() throws Exception {
        OrganismState child = buildOrganismState(2).toBuilder()
                .setParentId(1)
                .setBirthTick(17L)
                .setGenomeHash(-4242L)
                .build();
        TickData tick = TickData.newBuilder()
                .setTickNumber(1L)
                .addOrganisms(buildOrganismState(1))
                .addOrganisms(child)
                .build();

        try (Connection conn = getConnectionWithSchema("run-reader-static")) {
            database.doCreateOrganismTables(conn);
            database.doWriteOrganismTick(conn, tick);
            database.doCommitOrganismWrites(conn);

            // The summary must be answerable from the stored state alone. Emptying the static
            // table makes any remaining dependency on it fail rather than pass unnoticed.
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM organisms");
            }
            conn.commit();
        }

        try (IDatabaseReader reader = database.createReader("run-reader-static")) {
            List<OrganismTickSummary> organisms = reader.readOrganismsAtTick(1L);

            assertThat(organisms).hasSize(2);
            OrganismTickSummary root = organisms.get(0);
            assertThat(root.parentId).isNull();

            OrganismTickSummary offspring = organisms.get(1);
            assertThat(offspring.parentId).isEqualTo(1);
            assertThat(offspring.birthTick).isEqualTo(17L);
            assertThat(offspring.genomeHash).isEqualTo(-4242L);
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
                    .addPrograms(org.evochora.datapipeline.api.contracts.ProgramArtifact.newBuilder()
                        .setProgramId("prog-" + organismId)
                        .putLabelValueToName(MAIN_LABEL_HASH, "main")
                        .putLabelValueToName(FAIL_LABEL_HASH, "fail")
                        .build())
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

            // Runtime view: cold path (blob) — flat register array: DR, LR, PDR, PLR, FDR, FLR, SDR, SLR
            assertThat(state.registers).hasSize(org.evochora.runtime.isa.RegisterBank.TOTAL_REGISTER_COUNT);
            RegisterValueView drv = state.registers.get(0); // DR0
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

    /** Label hashes of the two frames written below; the run metadata maps them to names. */
    private static final int MAIN_LABEL_HASH = 101;
    private static final int FAIL_LABEL_HASH = 202;

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
                .addAllRegisters(ProtoTestUtils.buildFlatRegisters(new int[]{7}, new int[][]{{2, 3}}, null, null))
                .addDataStack(RegisterValue.newBuilder().setScalar(9).build())
                .addLocationStack(Vector.newBuilder().addComponents(4).build())
                .addCallStack(ProcFrame.newBuilder()
                        .setLabelHash(MAIN_LABEL_HASH)
                        .setAbsoluteReturnIp(Vector.newBuilder().addComponents(10).build())
                        .build())
                .setInstructionFailed(true)
                .setFailureReason("test-failure")
                .addFailureCallStack(ProcFrame.newBuilder()
                        .setLabelHash(FAIL_LABEL_HASH)
                        .setAbsoluteReturnIp(Vector.newBuilder().addComponents(11).build())
                        .build())
                .build();
    }
}


