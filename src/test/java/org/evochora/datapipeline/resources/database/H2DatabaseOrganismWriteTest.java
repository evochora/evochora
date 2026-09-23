package org.evochora.datapipeline.resources.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.OrganismStateList;
import org.evochora.datapipeline.api.contracts.ProcFrame;
import org.evochora.datapipeline.api.contracts.RegisterValue;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.datapipeline.utils.compression.CompressionCodecFactory;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.test.utils.ProtoTestUtils;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.dto.GenomeCarriers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import com.typesafe.config.ConfigFactory;

/**
 * Tests H2Database organism write path: schema creation, MERGE idempotency and
 * runtime_state_blob round-trip.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class H2DatabaseOrganismWriteTest {

    @TempDir
    Path tempDir;

    private H2Database database;

    @BeforeAll
    static void initInstructionSet() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        String dbPath = tempDir.toString().replace("\\", "/");
        var config = ConfigFactory.parseString("""
            jdbcUrl = "jdbc:h2:file:%s/test-organism-write;MODE=PostgreSQL"
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
    void createOrganismTables_isIdempotent() throws Exception {
        try (Connection conn = getConnectionWithSchema("run-1")) {
            database.doCreateOrganismTables(conn);
            database.doCreateOrganismTables(conn); // second call must not fail

            assertThat(tableExists(conn, "organisms")).isTrue();
            // SingleBlobOrgStrategy uses organism_ticks instead of organism_states
            assertThat(tableExists(conn, "organism_ticks")).isTrue();
        }
    }

    @Test
    void writeOrganismStates_isIdempotentAndBlobRoundTripWorks() throws Exception {
        TickData tick = TickData.newBuilder()
                .setTickNumber(1L)
                .addOrganisms(buildOrganismState(1))
                .build();

        try (Connection conn = getConnectionWithSchema("run-2")) {
            // Ensure tables exist
            database.doCreateOrganismTables(conn);

            // First write
            database.doWriteOrganismTick(conn, tick, java.util.Map.of());
            database.doCommitOrganismWrites(conn);
            // Idempotent second write
            database.doWriteOrganismTick(conn, tick, java.util.Map.of());
            database.doCommitOrganismWrites(conn);

            // organisms: single row
            try (ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) AS cnt FROM organisms")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("cnt")).isEqualTo(1);
            }

            // organism_ticks: single row (SingleBlobOrgStrategy stores all organisms per tick in one BLOB)
            try (ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) AS cnt FROM organism_ticks")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("cnt")).isEqualTo(1);
            }

            // organisms_blob round-trip (BLOB contains OrganismStateList)
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT organisms_blob FROM organism_ticks WHERE tick_number = ?")) {
                stmt.setLong(1, 1L);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    byte[] blob = rs.getBytes("organisms_blob");
                    assertThat(blob).isNotNull();
                    assertThat(blob.length).isGreaterThan(0);

                    // Detect codec from magic bytes and decompress
                    var codec = CompressionCodecFactory.detectFromMagicBytes(blob);
                    java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(blob);
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    try (java.io.InputStream in = codec.wrapInputStream(bis)) {
                        in.transferTo(bos);
                    }
                    byte[] decompressed = bos.toByteArray();

                    // BLOB contains OrganismStateList (all organisms for this tick)
                    OrganismStateList orgList = OrganismStateList.parseFrom(decompressed);
                    assertThat(orgList.getOrganismsCount()).isEqualTo(1);
                    
                    OrganismState state = orgList.getOrganisms(0);
                    assertThat(state.getOrganismId()).isEqualTo(1);
                    assertThat(state.getRegistersCount()).isEqualTo(org.evochora.runtime.isa.RegisterBank.TOTAL_REGISTER_COUNT);
                    assertThat(state.getCallStackCount()).isEqualTo(1);
                    assertThat(state.getInstructionFailed()).isTrue();
                    assertThat(state.hasFailureReason()).isTrue();
                    assertThat(state.getFailureReason()).isEqualTo("test-failure");
                    
                    // Verify instruction execution data
                    assertThat(state.hasInstructionOpcodeId()).isTrue();
                    assertThat(state.getInstructionOpcodeId()).isEqualTo(Instruction.getInstructionIdByName("SETI") | Config.TYPE_CODE);
                    assertThat(state.getInstructionRawArgumentsCount()).isEqualTo(2);
                    assertThat(state.hasInstructionEnergyCost()).isTrue();
                    assertThat(state.getInstructionEnergyCost()).isEqualTo(5);
                    assertThat(state.hasIpBeforeFetch()).isTrue();
                    assertThat(state.getIpBeforeFetch().getComponentsCount()).isEqualTo(2);
                    assertThat(state.hasDvBeforeFetch()).isTrue();
                    assertThat(state.getDvBeforeFetch().getComponentsCount()).isEqualTo(2);
                }
            }
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

    private boolean tableExists(Connection conn, String tableName) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getTables(null, null, tableName.toUpperCase(), null)) {
            return rs.next();
        }
    }

    /**
     * The clade view asks how many organisms carry which genome at a handful of ticks. Both
     * organism storage strategies must answer that the same way, because the view is served
     * whatever layout a run was written with - the blob strategy counts what it decodes anyway,
     * the row strategy counts in the database.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "org.evochora.datapipeline.resources.database.h2.SingleBlobOrgStrategy",
        "org.evochora.datapipeline.resources.database.h2.RowPerOrganismStrategy"
    })
    void countGenomesAtTicks_agreesBetweenStorageStrategies(String strategyClass) throws Exception {
        String dbPath = tempDir.toString().replace("\\", "/");
        try (H2Database db = new H2Database("counts-db", ConfigFactory.parseString("""
                jdbcUrl = "jdbc:h2:file:%s/test-counts-%s;MODE=PostgreSQL"
                h2OrganismStrategy { className = "%s" }
                """.formatted(dbPath, strategyClass.substring(strategyClass.lastIndexOf('.') + 1),
                              strategyClass)))) {
            String runId = "counts-run";
            try (Connection conn = connectionWithSchema(db, runId)) {
                db.doCreateOrganismTables(conn);
                db.doWriteOrganismTick(conn, tickWithGenomes(10L, Map.of(1, 700L, 2, 700L, 3, 800L)),
                        java.util.Map.of());
                db.doWriteOrganismTick(conn, tickWithGenomes(20L, Map.of(1, 700L, 3, 800L, 4, 900L)),
                        java.util.Map.of());
                db.doCommitOrganismWrites(conn);
            }

            try (IDatabaseReader reader = db.createReader(runId)) {
                List<GenomeCarriers> counts = reader.readGenomeCounts(List.of(10L, 20L));

                assertThat(counts).containsExactlyInAnyOrder(
                        new GenomeCarriers(10L, 700L, 2),
                        new GenomeCarriers(10L, 800L, 1),
                        new GenomeCarriers(20L, 700L, 1),
                        new GenomeCarriers(20L, 800L, 1),
                        new GenomeCarriers(20L, 900L, 1));
            }
        }
    }

    /** A tick holding one organism per entry, each carrying the genome the map gives it. */
    private TickData tickWithGenomes(long tickNumber, Map<Integer, Long> genomeByOrganism) {
        TickData.Builder tick = TickData.newBuilder().setTickNumber(tickNumber);
        genomeByOrganism.forEach((organismId, genomeHash) ->
                tick.addOrganisms(buildOrganismState(organismId).toBuilder()
                        .setGenomeHash(genomeHash)
                        .build()));
        return tick.build();
    }

    private Connection connectionWithSchema(H2Database db, String runId) throws SQLException {
        try {
            java.lang.reflect.Field dataSourceField = H2Database.class.getDeclaredField("dataSource");
            dataSourceField.setAccessible(true);
            @SuppressWarnings("resource")
            com.zaxxer.hikari.HikariDataSource dataSource =
                    (com.zaxxer.hikari.HikariDataSource) dataSourceField.get(db);
            Connection conn = dataSource.getConnection();
            org.evochora.datapipeline.utils.H2SchemaUtil.setupRunSchema(conn, runId, (c, schemaName) -> { });
            return conn;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private OrganismState buildOrganismState(int id) {
        Vector ip = Vector.newBuilder().addComponents(1).build();
        Vector dv = Vector.newBuilder().addComponents(0).addComponents(1).build();
        Vector ipBeforeFetch = Vector.newBuilder().addComponents(1).addComponents(2).build();
        Vector dvBeforeFetch = Vector.newBuilder().addComponents(0).addComponents(1).build();

        // SETI %DR0, DATA:42 instruction
        // Opcode: SETI, taken from the instruction registry
        int setiOpcode = Instruction.getInstructionIdByName("SETI") | Config.TYPE_CODE;
        // Register argument: %DR0 encoded as DATA:0
        int regArg = new Molecule(Config.TYPE_DATA, 0).toInt();
        // Immediate argument: DATA:42
        int immArg = new Molecule(Config.TYPE_DATA, 42).toInt();

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
                        .setAbsoluteReturnIp(Vector.newBuilder().addComponents(10).build())
                        .build())
                .setInstructionFailed(true)
                .setFailureReason("test-failure")
                .addFailureCallStack(ProcFrame.newBuilder()
                        .setAbsoluteReturnIp(Vector.newBuilder().addComponents(11).build())
                        .build())
                // Instruction execution data
                .setInstructionOpcodeId(setiOpcode)
                .addInstructionRawArguments(regArg)
                .addInstructionRawArguments(immArg)
                .setInstructionEnergyCost(5)
                .setIpBeforeFetch(ipBeforeFetch)
                .setDvBeforeFetch(dvBeforeFetch)
                // Add register values before execution (required for annotation display)
                // SETI %DR0, DATA:10 - first argument is REGISTER (registerId=0)
                .putInstructionRegisterValuesBefore(0, RegisterValue.newBuilder().setScalar(42).build())
                .build();
    }
}


