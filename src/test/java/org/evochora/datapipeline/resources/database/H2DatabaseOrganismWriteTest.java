package org.evochora.datapipeline.resources.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.OrganismStateList;
import org.evochora.datapipeline.api.contracts.ProcFrame;
import org.evochora.datapipeline.api.contracts.RegisterValue;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.dto.OrganismStaticInfo;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.typesafe.config.ConfigFactory;

/**
 * Tests H2Database organism write path: schema creation, MERGE idempotency and
 * runtime_state_blob round-trip.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class H2DatabaseOrganismWriteTest {

    private H2Database database;

    @BeforeAll
    static void initInstructionSet() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        var config = ConfigFactory.parseString("""
            jdbcUrl = "jdbc:h2:mem:test-organism-write-%s;MODE=PostgreSQL"
            """.formatted(UUID.randomUUID()));

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

    /**
     * An organism's death arrives at its last appearance, which for a long-lived one is not the
     * tick its static data was written at. Both storage strategies must still end up with the
     * death tick in its row, including when birth and death fall into the same commit window.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "org.evochora.datapipeline.resources.database.h2.SingleBlobOrgStrategy",
        "org.evochora.datapipeline.resources.database.h2.RowPerOrganismStrategy"
    })
    void deathTick_reachesTheRowWhateverTickItArrivesAt(String strategyClass) throws Exception {
        try (H2Database db = new H2Database("death-db", ConfigFactory.parseString("""
                jdbcUrl = "jdbc:h2:mem:test-death-%s;MODE=PostgreSQL"
                h2OrganismStrategy { className = "%s" }
                """.formatted(UUID.randomUUID(), strategyClass)))) {
            String runId = "death-run";
            try (Connection conn = getConnectionWithSchema(db, runId)) {
                db.doCreateOrganismTables(conn);
                // Organism 1 lives on, organism 2 is born and dies within the same commit window
                db.doWriteOrganismTick(conn, tick(10L, alive(1, 0L)), java.util.Map.of());
                db.doWriteOrganismTick(conn, tick(20L, alive(1, 0L), alive(2, 15L)),
                        java.util.Map.of());
                db.doWriteOrganismTick(conn, tick(30L, alive(1, 0L), dead(2, 15L, 25L)),
                        java.util.Map.of());
                db.doCommitOrganismWrites(conn);
            }

            try (IDatabaseReader reader = db.createReader(runId)) {
                assertThat(reader.readOrganismStaticInfo(1).deathTick).isEqualTo(-1L);

                OrganismStaticInfo shortLived = reader.readOrganismStaticInfo(2);
                assertThat(shortLived.birthTick).isEqualTo(15L);
                assertThat(shortLived.deathTick).isEqualTo(25L);
                assertThat(shortLived.aliveAt(20L)).isTrue();
                assertThat(shortLived.aliveAt(25L)).isFalse();
                assertThat(shortLived.aliveAt(10L)).isFalse();

                assertThat(reader.readOrganismStaticInfo(99)).isNull();
            }
        }
    }

    /** A tick holding exactly the given organisms. */
    private TickData tick(long tickNumber, OrganismState... organisms) {
        TickData.Builder tick = TickData.newBuilder().setTickNumber(tickNumber);
        for (OrganismState organism : organisms) {
            tick.addOrganisms(organism);
        }
        return tick.build();
    }

    /** An organism alive since the given tick. */
    private OrganismState alive(int id, long birthTick) {
        return buildOrganismState(id).toBuilder()
                .setBirthTick(birthTick)
                .build();
    }

    /** An organism in its final appearance: dead, with the tick it died at. */
    private OrganismState dead(int id, long birthTick, long deathTick) {
        return alive(id, birthTick).toBuilder()
                .setIsDead(true)
                .setDeathTick(deathTick)
                .build();
    }

    private Connection getConnectionWithSchema(String runId) throws SQLException {
        return getConnectionWithSchema(database, runId);
    }

    private Connection getConnectionWithSchema(H2Database db, String runId) throws SQLException {
        try {
            java.lang.reflect.Field dataSourceField = H2Database.class.getDeclaredField("dataSource");
            dataSourceField.setAccessible(true);
            @SuppressWarnings("resource")
            com.zaxxer.hikari.HikariDataSource dataSource =
                    (com.zaxxer.hikari.HikariDataSource) dataSourceField.get(db);

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


