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
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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
            database.doWriteOrganismStates(conn, java.util.List.of(tick));
            // Idempotent second write
            database.doWriteOrganismStates(conn, java.util.List.of(tick));

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
                    assertThat(state.getDataRegistersCount()).isEqualTo(1);
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

    private OrganismState buildOrganismState(int id) {
        Vector ip = Vector.newBuilder().addComponents(1).build();
        Vector dv = Vector.newBuilder().addComponents(0).addComponents(1).build();
        Vector ipBeforeFetch = Vector.newBuilder().addComponents(1).addComponents(2).build();
        Vector dvBeforeFetch = Vector.newBuilder().addComponents(0).addComponents(1).build();

        // SETI %DR0, DATA:42 instruction
        // Opcode: SETI (ID 1) | TYPE_CODE
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


