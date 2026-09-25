package org.evochora.datapipeline.resources.database.h2;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.evochora.datapipeline.api.contracts.MutationEvent;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.PersistentRegisterStore;
import org.evochora.datapipeline.api.contracts.ProcFrame;
import org.evochora.datapipeline.api.contracts.ProcedureRegisterSnapshot;
import org.evochora.datapipeline.api.contracts.RegisterValue;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.typesafe.config.ConfigFactory;

/**
 * Tests that every organism storage strategy gives back the whole per-tick state it was given.
 * <p>
 * {@link IH2OrgStorageStrategy#readSingleOrganismState} promises an {@link OrganismState}, and the
 * detail view of an organism is built from it. A strategy that lays the state out in columns of
 * its own copies it field by field, and a field it does not copy is gone without a trace. The
 * organism written here therefore has every field set, and the one read back must carry all of
 * them, whichever strategy stored it.
 * <p>
 * The fields that never change over an organism's life are the exception. They stand in the
 * shared {@code organisms} table and are read from there only, so that none of them ever needs a
 * blob to be unpacked; what a strategy returns for them is not read by anyone.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class OrgStorageStrategyRoundTripTest {

    private static final long TICK = 1000L;

    /** The fields of the {@code organisms} table, which the round trip does not compare. */
    private static final Set<String> STATIC_FIELDS = Set.of(
        "organism_id", "parent_id", "birth_tick", "program_id", "initial_position",
        "genome_hash", "generation", "parent_genome_hash", "birth_mutations");

    static Stream<Named<AbstractH2OrgStorageStrategy>> strategies() {
        return Stream.of(
            Named.of("SingleBlobOrgStrategy", new SingleBlobOrgStrategy(ConfigFactory.empty())),
            Named.of("RowPerOrganismStrategy", new RowPerOrganismStrategy(ConfigFactory.empty())));
    }

    @Test
    void theOrganismWrittenHasEveryFieldSet() {
        // Guards the round trip below: a field added to OrganismState and not set here would
        // pass it unchecked, however a strategy treats it
        List<String> unset = new ArrayList<>();
        for (FieldDescriptor field : OrganismState.getDescriptor().getFields()) {
            if (!fullOrganism().getAllFields().containsKey(field)) {
                unset.add(field.getName());
            }
        }
        assertThat(unset).as("fields of OrganismState the test organism leaves unset").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void readsBackEveryPerTickFieldItWasGiven(AbstractH2OrgStorageStrategy strategy)
            throws SQLException {
        OrganismState written = fullOrganism();
        try (Connection conn = openDatabase()) {
            strategy.createTables(conn);
            strategy.addOrganismTick(conn,
                TickData.newBuilder().setTickNumber(TICK).addOrganisms(written).build(),
                Map.of());
            strategy.commitOrganismWrites(conn);
            conn.commit();

            OrganismState read = strategy.readSingleOrganismState(
                conn, TICK, written.getOrganismId());

            assertThat(read).as("the organism read back").isNotNull();
            List<String> lost = new ArrayList<>();
            for (FieldDescriptor field : OrganismState.getDescriptor().getFields()) {
                if (STATIC_FIELDS.contains(field.getName())) {
                    continue;
                }
                if (!written.getField(field).equals(read.getField(field))) {
                    lost.add(field.getName());
                }
            }
            assertThat(lost).as("fields that did not come back as written").isEmpty();
        }
    }

    // ==================== Helper Methods ====================

    private static Connection openDatabase() throws SQLException {
        Connection conn = DriverManager.getConnection(
            "jdbc:h2:mem:org-round-trip-" + UUID.randomUUID() + ";MODE=PostgreSQL");
        conn.setAutoCommit(false);
        return conn;
    }

    private static Vector vector(int x, int y) {
        return Vector.newBuilder().addComponents(x).addComponents(y).build();
    }

    private static RegisterValue scalar(int value) {
        return RegisterValue.newBuilder().setScalar(value).build();
    }

    private static ProcFrame frame(int labelHash) {
        return ProcFrame.newBuilder()
            .setLabelHash(labelHash)
            .setAbsoluteReturnIp(vector(4, 5))
            .addSavedRegisters(scalar(6))
            .setAbsoluteCallIp(vector(7, 8))
            .build();
    }

    /** An organism with every field of {@link OrganismState} set to a value other than its default. */
    private static OrganismState fullOrganism() {
        return OrganismState.newBuilder()
            .setOrganismId(7)
            .setParentId(3)
            .setBirthTick(900L)
            .setProgramId("prog")
            .setEnergy(100)
            .setIp(vector(2, 3))
            .setInitialPosition(vector(1, 1))
            .setDv(vector(1, 0))
            .addDataPointers(vector(2, 4))
            .addDataPointers(vector(5, 6))
            .setActiveDpIndex(1)
            .addRegisters(scalar(11))
            .addRegisters(RegisterValue.newBuilder().setVector(vector(12, 13)).build())
            .addDataStack(scalar(14))
            .addLocationStack(vector(15, 16))
            .addCallStack(frame(17))
            .setIsDead(true)
            .setInstructionFailed(true)
            .setFailureReason("reason")
            .addFailureCallStack(frame(18))
            .setInstructionOpcodeId(19)
            .addInstructionRawArguments(20)
            .setInstructionEnergyCost(21)
            .setInstructionEntropyDelta(22)
            .setIpBeforeFetch(vector(23, 24))
            .setDvBeforeFetch(vector(0, 1))
            .putInstructionRegisterValuesBefore(25, scalar(26))
            .setEntropyRegister(27)
            .setMoleculeMarkerRegister(28)
            .setGenomeHash(29L)
            .setDeathTick(1000L)
            .setNextInstructionOpcodeId(30)
            .addNextInstructionRawArguments(31)
            .putNextInstructionRegisterValuesBefore(32, scalar(33))
            .setPersistentRegisterStore(PersistentRegisterStore.newBuilder()
                .addProcedureSnapshots(ProcedureRegisterSnapshot.newBuilder()
                    .setLabelHash(34)
                    .addRegisters(scalar(35))))
            .setCurrentProcLabelHash(36)
            .setStackSavedDirty(true)
            .setPersistentDirty(true)
            .setGeneration(37)
            .setParentGenomeHash(38L)
            .addBirthMutations(MutationEvent.newBuilder()
                .setPluginClass("org.evochora.runtime.worldgen.GeneSubstitutionPlugin")
                .setKind("substitution")
                .addCells(39)
                .addOldValues(40)
                .addNewValues(41)
                .addParams(42L)
                .addDv(1))
            .build();
    }
}
