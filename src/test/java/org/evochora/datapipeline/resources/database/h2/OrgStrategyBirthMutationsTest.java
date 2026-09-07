package org.evochora.datapipeline.resources.database.h2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.StoredMutationEvent;
import org.evochora.datapipeline.api.contracts.StoredMutationEvents;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.typesafe.config.ConfigFactory;

/**
 * Tests the birth mutations column of the shared {@code organisms} table against a real H2
 * database, for every organism storage strategy.
 * <p>
 * The column and the second MERGE statement live in {@link AbstractH2OrgStorageStrategy}, so both
 * strategies must store and keep the bytes the same way. What is checked here is the part a mock
 * cannot show: that the value arrives in the column, that the statement writing it depends on the
 * static data of the same commit window, and that a later window without events leaves it alone.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class OrgStrategyBirthMutationsTest {

    private static final int ORGANISM_ID = 7;

    static Stream<Named<AbstractH2OrgStorageStrategy>> strategies() {
        return Stream.of(
            Named.of("SingleBlobOrgStrategy", new SingleBlobOrgStrategy(ConfigFactory.empty())),
            Named.of("RowPerOrganismStrategy", new RowPerOrganismStrategy(ConfigFactory.empty())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void storesTheEventsInTheColumnAndReadsThemBack(AbstractH2OrgStorageStrategy strategy)
            throws SQLException {
        try (Connection conn = openDatabase()) {
            strategy.createTables(conn);

            StoredMutationEvents events = events();
            strategy.addOrganismTick(conn, tickWith(1000L),
                Map.of(ORGANISM_ID, events.toByteArray()));
            strategy.commitOrganismWrites(conn);
            conn.commit();

            assertThat(readBirthMutations(conn)).isEqualTo(events);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void theEventStatementAloneCannotCreateTheRow(AbstractH2OrgStorageStrategy strategy)
            throws SQLException {
        try (Connection conn = openDatabase()) {
            strategy.createTables(conn);

            // The event statement names only the organism id and the events, so on its own it
            // would insert a row without the values the table requires. This is why the static
            // data of the same commit window is written first.
            AbstractH2OrgStorageStrategy.StreamingSession session =
                strategy.ensureStreamingSession(conn);
            strategy.addBirthMutationsBatch(session, tickWith(1000L),
                Map.of(ORGANISM_ID, events().toByteArray()));

            assertThatThrownBy(() -> session.birthMutationsStmt().executeBatch())
                .isInstanceOf(SQLException.class);
            conn.rollback();
            strategy.resetStreamingState(conn);

            // The pair, in the order the strategy runs it, succeeds on the same empty table
            strategy.addOrganismTick(conn, tickWith(1000L),
                Map.of(ORGANISM_ID, events().toByteArray()));
            strategy.commitOrganismWrites(conn);
            conn.commit();

            assertThat(readBirthMutations(conn)).isEqualTo(events());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void aLaterWindowWithoutEventsLeavesTheColumnUntouched(AbstractH2OrgStorageStrategy strategy)
            throws SQLException {
        try (Connection conn = openDatabase()) {
            strategy.createTables(conn);

            strategy.addOrganismTick(conn, tickWith(1000L),
                Map.of(ORGANISM_ID, events().toByteArray()));
            strategy.commitOrganismWrites(conn);
            conn.commit();

            // The organism lives on and its static row is merged again in every later window,
            // without events: an event stands in the data once
            strategy.addOrganismTick(conn, tickWith(1001L), Map.of());
            strategy.commitOrganismWrites(conn);
            conn.commit();

            assertThat(readBirthMutations(conn)).isEqualTo(events());
        }
    }

    // ==================== Helper Methods ====================

    private static Connection openDatabase() throws SQLException {
        Connection conn = DriverManager.getConnection(
            "jdbc:h2:mem:org-birth-mutations-" + UUID.randomUUID() + ";MODE=PostgreSQL");
        conn.setAutoCommit(false);
        return conn;
    }

    private static StoredMutationEvents events() {
        return StoredMutationEvents.newBuilder()
            .setDimensions(2)
            .addEvents(StoredMutationEvent.newBuilder()
                .setPluginClass("org.evochora.runtime.worldgen.GeneSubstitutionPlugin")
                .setKind("substitution")
                .addAllRelativeCoordinates(java.util.List.of(3, -4))
                .addOldValues(11)
                .addNewValues(12)
                .addAllDv(java.util.List.of(1, 0)))
            .build();
    }

    private static TickData tickWith(long tickNumber) {
        return TickData.newBuilder()
            .setTickNumber(tickNumber)
            .addOrganisms(OrganismState.newBuilder()
                .setOrganismId(ORGANISM_ID)
                .setBirthTick(999L)
                .setProgramId("prog")
                .setInitialPosition(Vector.newBuilder().addComponents(2).addComponents(3).build())
                .setEnergy(100)
                .setIp(Vector.newBuilder().addComponents(2).addComponents(3).build())
                .setDv(Vector.newBuilder().addComponents(1).addComponents(0).build())
                .addDataPointers(Vector.newBuilder().addComponents(2).addComponents(3).build())
                .setActiveDpIndex(0)
                .build())
            .build();
    }

    private static StoredMutationEvents readBirthMutations(Connection conn) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT birth_mutations FROM organisms WHERE organism_id = ?")) {
            stmt.setInt(1, ORGANISM_ID);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).as("the organism's static row").isTrue();
                byte[] stored = rs.getBytes(1);
                assertThat(stored).as("the birth mutations column").isNotNull();
                try {
                    return StoredMutationEvents.parseFrom(stored);
                } catch (com.google.protobuf.InvalidProtocolBufferException e) {
                    throw new SQLException("The column does not hold a StoredMutationEvents message", e);
                }
            }
        }
    }
}
