package org.evochora.datapipeline.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.isa.RegisterBank;
import org.evochora.runtime.worldgen.LabelRewritePlugin;
import org.evochora.test.utils.ProtoTestUtils;
import org.evochora.runtime.spi.IRandomProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link SimulationRestorer}.
 * <p>
 * Tests that simulation state is correctly restored from a snapshot.
 * Since resume always happens from a snapshot (chunk start), there is
 * no accumulated delta handling to test.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
@AllowLog(level = LogLevel.INFO, loggerPattern = ".*SimulationRestorer.*")
class SimulationRestorerTest {

    private static final String TEST_RUN_ID = "20250127-123456-test-run";

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    private IRandomProvider randomProvider;

    @BeforeEach
    void setUp() {
        // A real provider: the restorer reads its seed and loads the checkpointed state into it
        randomProvider = new SeededRandomProvider(42L);
    }

    // ==================== Happy Path Tests ====================

    @Test
    void restore_FromSnapshot_CreatesSimulation() {
        // Create minimal metadata
        SimulationMetadata metadata = createMinimalMetadata();

        // Create snapshot with one organism
        TickData snapshot = createSnapshot(1000, 100);

        // Create checkpoint (always from snapshot)
        ResumeCheckpoint checkpoint = new ResumeCheckpoint(metadata, snapshot);

        // Restore
        SimulationRestorer.RestoredState state = SimulationRestorer.restore(checkpoint, randomProvider, 1);
        Simulation simulation = state.simulation();

        // Verify simulation state
        assertThat(simulation.getCurrentTick())
                .as("snapshot 1000 holds the state after tick 1000; the simulation continues with tick 1001")
                .isEqualTo(1001);
        assertThat(simulation.getTotalOrganismsCreatedCount()).isEqualTo(100);
        assertThat(simulation.getEnvironment().getShape()).isEqualTo(new int[]{100, 100});
        assertThat(simulation.getOrganisms()).hasSize(1);
    }

    @Test
    void restore_OrganismState_AllFieldsRestored() {
        SimulationMetadata metadata = createMinimalMetadata();

        // Create detailed organism state
        OrganismState orgState = OrganismState.newBuilder()
            .setOrganismId(42)
            .setParentId(10)
            .setBirthTick(500)
            .setProgramId("test-program")
            .setEnergy(1000)
            .setIp(createVector(25, 30))
            .setDv(createVector(1, 0))
            .setInitialPosition(createVector(20, 25))
            .setEntropyRegister(50)
            .setMoleculeMarkerRegister(3)
            .addDataPointers(createVector(10, 10))
            .addDataPointers(createVector(20, 20))
            .setActiveDpIndex(1)
            .addAllRegisters(ProtoTestUtils.buildFlatRegisters(new int[]{100}, null, new int[]{200}, null))
            .setIsDead(false)
            .build();

        TickData snapshot = TickData.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setTickNumber(500)
            .setCaptureTimeMs(System.currentTimeMillis())
            .setTotalOrganismsCreated(50)
            .setCellColumns(CellDataColumns.newBuilder().build())
            .setRngState(validRngState())
            .addOrganisms(orgState)
            .build();

        ResumeCheckpoint checkpoint = new ResumeCheckpoint(metadata, snapshot);
        SimulationRestorer.RestoredState state = SimulationRestorer.restore(checkpoint, randomProvider, 1);
        Simulation simulation = state.simulation();

        assertThat(simulation.getOrganisms()).hasSize(1);
        Organism org = simulation.getOrganisms().get(0);

        assertThat(org.getId()).isEqualTo(42);
        assertThat(org.getParentId()).isEqualTo(10);
        assertThat(org.getBirthTick()).isEqualTo(500);
        assertThat(org.getProgramId()).isEqualTo("test-program");
        assertThat(org.getEr()).isEqualTo(1000);
        assertThat(org.getIp()).isEqualTo(new int[]{25, 30});
        assertThat(org.getDv()).isEqualTo(new int[]{1, 0});
        assertThat(org.getSr()).isEqualTo(50);
        assertThat(org.getMr()).isEqualTo(3);
        assertThat(org.getActiveDpIndex()).isEqualTo(1);
        assertThat(org.readOperand(0)).isEqualTo(100);
        assertThat(org.readOperand(RegisterBank.PDR.base)).isEqualTo(200);
    }

    @Test
    void restore_DeadOrganisms_Restored() {
        SimulationMetadata metadata = createMinimalMetadata();

        // Create snapshot with one live and one dead organism
        OrganismState liveOrg = createOrganismState(1, 500);
        OrganismState deadOrg = OrganismState.newBuilder()
            .setOrganismId(2)
            .setBirthTick(100)
            .setEnergy(0)
            .setIp(createVector(0, 0))
            .setDv(createVector(1, 0))
            .setInitialPosition(createVector(0, 0))
            .addAllRegisters(ProtoTestUtils.buildFlatRegisters(null, null, null, null))
            .addDataPointers(createVector(0, 0))
            .addDataPointers(createVector(0, 0))
            .setIsDead(true)
            .setDeathTick(999)
            .build();

        TickData snapshot = TickData.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setTickNumber(1000)
            .setCaptureTimeMs(System.currentTimeMillis())
            .setTotalOrganismsCreated(100)
            .setCellColumns(CellDataColumns.newBuilder().build())
            .setRngState(validRngState())
            .addOrganisms(liveOrg)
            .addOrganisms(deadOrg)
            .build();

        ResumeCheckpoint checkpoint = new ResumeCheckpoint(metadata, snapshot);
        SimulationRestorer.RestoredState state = SimulationRestorer.restore(checkpoint, randomProvider, 1);
        Simulation simulation = state.simulation();

        // Both organisms should be restored (dead organisms are pruned after serialization, not on restore)
        assertThat(simulation.getOrganisms()).hasSize(2);
        assertThat(simulation.getOrganisms().get(0).getId()).isEqualTo(1);
        assertThat(simulation.getOrganisms().get(1).getId()).isEqualTo(2);
        assertThat(simulation.getOrganisms().get(1).isDead()).isTrue();
        assertThat(simulation.getOrganisms().get(1).getDeathTick()).isEqualTo(999);
    }

    @Test
    void restore_CellData_EnvironmentPopulated() {
        SimulationMetadata metadata = createMinimalMetadata();

        // Create cell data with some cells
        // flatIndexToCoord with shape [100, 100] and flatIndex 510:
        //   coord[1] = 510 % 100 = 10
        //   coord[0] = 510 / 100 = 5
        // So flatIndex 510 -> coord [5, 10]
        //
        // Pack molecule data: type=TYPE_DATA, value=42, marker=0
        int packedMolecule = Config.TYPE_DATA | 42;

        CellDataColumns cells = CellDataColumns.newBuilder()
            .addFlatIndices(510)  // coord [5, 10]
            .addMoleculeData(packedMolecule)
            .addOwnerIds(7)
            .build();

        TickData snapshot = TickData.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setTickNumber(1000)
            .setCaptureTimeMs(System.currentTimeMillis())
            .setTotalOrganismsCreated(50)
            .setCellColumns(cells)
            .setRngState(validRngState())
            .build();

        ResumeCheckpoint checkpoint = new ResumeCheckpoint(metadata, snapshot);
        SimulationRestorer.RestoredState state = SimulationRestorer.restore(checkpoint, randomProvider, 1);
        Simulation simulation = state.simulation();

        // Verify cell was set at coord [5, 10]
        var molecule = simulation.getEnvironment().getMolecule(5, 10);
        assertThat(molecule.type()).isEqualTo(Config.TYPE_DATA);  // type() returns shifted value
        assertThat(molecule.value()).isEqualTo(42);
        assertThat(simulation.getEnvironment().getOwnerId(5, 10)).isEqualTo(7);
    }

    // ==================== Genome Hash Restoration ====================

    @Test
    void restore_GenomeHashes_RestoredFromSnapshot() {
        SimulationMetadata metadata = createMinimalMetadata();

        TickData snapshot = TickData.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setTickNumber(1000)
            .setCaptureTimeMs(System.currentTimeMillis())
            .setTotalOrganismsCreated(100)
            .setTotalUniqueGenomes(3)
            .addAllGenomeHashesEverSeen(111L)
            .addAllGenomeHashesEverSeen(222L)
            .addAllGenomeHashesEverSeen(333L)
            .setCellColumns(CellDataColumns.newBuilder().build())
            .setRngState(validRngState())
            .addOrganisms(createOrganismState(1, 500))
            .build();

        ResumeCheckpoint checkpoint = new ResumeCheckpoint(metadata, snapshot);
        SimulationRestorer.RestoredState state = SimulationRestorer.restore(checkpoint, randomProvider, 1);
        Simulation simulation = state.simulation();

        assertThat(simulation.getTotalUniqueGenomesCount()).isEqualTo(3);
        assertThat(simulation.getAllGenomesEverSeen()).containsExactlyInAnyOrder(111L, 222L, 333L);
    }

    @Test
    void restore_FailureCallStack_Preserved() {
        SimulationMetadata metadata = createMinimalMetadata();

        // Build a ProcFrame for the failure call stack
        org.evochora.datapipeline.api.contracts.ProcFrame protoFrame =
                org.evochora.datapipeline.api.contracts.ProcFrame.newBuilder()
                    .setLabelHash(12345)
                    .setAbsoluteReturnIp(createVector(5, 0))
                    .setAbsoluteCallIp(createVector(3, 0))
                    .build();

        OrganismState failedOrg = OrganismState.newBuilder()
            .setOrganismId(99)
            .setBirthTick(0)
            .setEnergy(500)
            .setIp(createVector(10, 10))
            .setDv(createVector(1, 0))
            .setInitialPosition(createVector(0, 0))
            .setInstructionFailed(true)
            .setFailureReason("Call stack overflow")
            .addFailureCallStack(protoFrame)
            .addAllRegisters(ProtoTestUtils.buildFlatRegisters(null, null, null, null))
            .addDataPointers(createVector(10, 10))
            .addDataPointers(createVector(10, 10))
            .setIsDead(false)
            .build();

        TickData snapshot = TickData.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setTickNumber(100)
            .setCaptureTimeMs(System.currentTimeMillis())
            .setTotalOrganismsCreated(10)
            .setCellColumns(CellDataColumns.newBuilder().build())
            .setRngState(validRngState())
            .addOrganisms(failedOrg)
            .build();

        ResumeCheckpoint checkpoint = new ResumeCheckpoint(metadata, snapshot);
        SimulationRestorer.RestoredState state = SimulationRestorer.restore(checkpoint, randomProvider, 1);
        Simulation simulation = state.simulation();

        assertThat(simulation.getOrganisms()).hasSize(1);
        Organism org = simulation.getOrganisms().get(0);

        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).isEqualTo("Call stack overflow");
        assertThat(org.getFailureCallStack()).isNotNull();
        assertThat(org.getFailureCallStack()).hasSize(1);
        assertThat(org.getFailureCallStack().peek().labelHash()).isEqualTo(12345);
    }

    // ==================== Call Stack Register Snapshots ====================

    /**
     * A call frame whose caller had not written any stack-saved register carries no snapshot.
     * At runtime that absence is expressed as {@code null}, and RET distinguishes it from a real
     * snapshot: {@code null} resets the stack-saved banks, a snapshot restores them.
     * <p>
     * Protobuf represents both an absent and an empty snapshot as an empty repeated field, so the
     * distinction has to be re-derived on restore. Restoring an empty array instead of
     * {@code null} would make RET attempt a restore from a zero-length snapshot, which the runtime
     * rejects — see {@link #restoreStackSavedRegisters_EmptySnapshot_Rejected()}.
     */
    @Test
    void restore_CallFrameWithoutRegisterSnapshot_KeepsSnapshotAbsent() {
        Organism organism = restoreOrganismWithCallFrame(
                org.evochora.datapipeline.api.contracts.ProcFrame.newBuilder()
                    .setLabelHash(4711)
                    .setAbsoluteReturnIp(createVector(5, 0))
                    .setAbsoluteCallIp(createVector(3, 0))
                    .build());

        assertThat(organism.getCallStack()).hasSize(1);
        assertThat(organism.getCallStack().peek().savedRegisters()).isNull();
    }

    @Test
    void restore_CallFrameWithRegisterSnapshot_RestoresValues() {
        org.evochora.datapipeline.api.contracts.ProcFrame.Builder frame =
                org.evochora.datapipeline.api.contracts.ProcFrame.newBuilder()
                    .setLabelHash(4712)
                    .setAbsoluteReturnIp(createVector(5, 0))
                    .setAbsoluteCallIp(createVector(3, 0));

        // One entry per stack-saved register slot, in RegisterBank declaration order. The first
        // PDR slot carries a recognizable value, the remaining slots stay at their defaults.
        for (RegisterBank bank : RegisterBank.allSavedOnCall()) {
            for (int i = 0; i < bank.count; i++) {
                int value = bank == RegisterBank.PDR && i == 0 ? 4242 : 0;
                frame.addSavedRegisters(bank.isLocation
                        ? org.evochora.datapipeline.api.contracts.RegisterValue.newBuilder()
                            .setVector(createVector(0, 0)).build()
                        : org.evochora.datapipeline.api.contracts.RegisterValue.newBuilder()
                            .setScalar(value).build());
            }
        }

        Organism organism = restoreOrganismWithCallFrame(frame.build());

        Object[] savedRegisters = organism.getCallStack().peek().savedRegisters();
        assertThat(savedRegisters).isNotNull();
        assertThat(savedRegisters).hasSize(RegisterBank.STACK_SAVED_SNAPSHOT_SIZE);
        assertThat(savedRegisters[0]).isEqualTo(4242);
    }

    /**
     * Documents why an absent snapshot must not become an empty array: the runtime accepts only a
     * snapshot of the exact stack-saved size.
     */
    @Test
    void restoreStackSavedRegisters_EmptySnapshot_Rejected() {
        Organism organism = restoreOrganismWithCallFrame(
                org.evochora.datapipeline.api.contracts.ProcFrame.newBuilder()
                    .setLabelHash(4713)
                    .setAbsoluteReturnIp(createVector(5, 0))
                    .setAbsoluteCallIp(createVector(3, 0))
                    .build());

        assertThatThrownBy(() -> organism.restoreStackSavedRegisters(new Object[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(RegisterBank.STACK_SAVED_SNAPSHOT_SIZE));
    }

    /** Restores a single organism whose call stack holds exactly the given frame. */
    private Organism restoreOrganismWithCallFrame(
            org.evochora.datapipeline.api.contracts.ProcFrame frame) {
        OrganismState organismState = OrganismState.newBuilder()
            .setOrganismId(7)
            .setBirthTick(0)
            .setEnergy(500)
            .setIp(createVector(10, 10))
            .setDv(createVector(1, 0))
            .setInitialPosition(createVector(0, 0))
            .addAllRegisters(ProtoTestUtils.buildFlatRegisters(null, null, null, null))
            .addDataPointers(createVector(10, 10))
            .addDataPointers(createVector(10, 10))
            .addCallStack(frame)
            .setIsDead(false)
            .build();

        TickData snapshot = TickData.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setTickNumber(100)
            .setCaptureTimeMs(System.currentTimeMillis())
            .setTotalOrganismsCreated(10)
            .setCellColumns(CellDataColumns.newBuilder().build())
            .setRngState(validRngState())
            .addOrganisms(organismState)
            .build();

        ResumeCheckpoint checkpoint = new ResumeCheckpoint(createMinimalMetadata(), snapshot);
        Simulation simulation = SimulationRestorer.restore(checkpoint, randomProvider, 1).simulation();

        assertThat(simulation.getOrganisms()).hasSize(1);
        return simulation.getOrganisms().get(0);
    }

    // ==================== Rejection of unreadable checkpoint data ====================
    //
    // None of the states below can be produced by a running simulation: the writer always emits a
    // complete organism, a complete snapshot and one plugin state per plugin. They arise from corrupt
    // data, a foreign schema or a future write-side defect. Restoring them anyway would continue a run
    // deterministically from state that is wrong, with nothing in the data or the log to show it.

    @Test
    void restore_SnapshotWithoutRngState_Rejected() {
        TickData snapshot = TickData.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setTickNumber(100)
            .setCaptureTimeMs(System.currentTimeMillis())
            .setTotalOrganismsCreated(10)
            .setCellColumns(CellDataColumns.newBuilder().build())
            .addOrganisms(createOrganismState(1, 500))
            .build();

        assertThatThrownBy(() -> restoreSnapshot(snapshot))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining("RNG")
                .hasMessageContaining("100");
    }

    @Test
    void restore_OrganismWithoutRegisters_Rejected() {
        OrganismState organism = wellFormedOrganism().clearRegisters().build();

        assertThatThrownBy(() -> restoreOrganism(organism))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining(String.valueOf(RegisterBank.TOTAL_REGISTER_COUNT));
    }

    @Test
    void restore_OrganismWithTooFewRegisters_Rejected() {
        OrganismState.Builder builder = wellFormedOrganism().clearRegisters();
        List<org.evochora.datapipeline.api.contracts.RegisterValue> registers =
                ProtoTestUtils.buildFlatRegisters(null, null, null, null);
        builder.addAllRegisters(registers.subList(0, registers.size() - 1));

        assertThatThrownBy(() -> restoreOrganism(builder.build()))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining(String.valueOf(RegisterBank.TOTAL_REGISTER_COUNT));
    }

    @Test
    void restore_PersistentSnapshotWithWrongRegisterCount_Rejected() {
        OrganismState organism = wellFormedOrganism()
            .setPersistentRegisterStore(org.evochora.datapipeline.api.contracts.PersistentRegisterStore.newBuilder()
                .addProcedureSnapshots(org.evochora.datapipeline.api.contracts.ProcedureRegisterSnapshot.newBuilder()
                    .setLabelHash(4711)
                    .addRegisters(scalar(0))
                    .build())
                .build())
            .build();

        assertThatThrownBy(() -> restoreOrganism(organism))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining(String.valueOf(RegisterBank.PERSISTENT_SNAPSHOT_SIZE));
    }

    /**
     * A partial stack-saved snapshot is a defect, while an entirely absent one is not — absence means
     * the caller had written no stack-saved register, and RET relies on the distinction. Only the
     * partial case is rejected here; {@link #restore_CallFrameWithoutRegisterSnapshot_KeepsSnapshotAbsent()}
     * pins the other side.
     */
    @Test
    void restore_CallFrameWithPartialRegisterSnapshot_Rejected() {
        org.evochora.datapipeline.api.contracts.ProcFrame frame =
                org.evochora.datapipeline.api.contracts.ProcFrame.newBuilder()
                    .setLabelHash(4714)
                    .setAbsoluteReturnIp(createVector(5, 0))
                    .setAbsoluteCallIp(createVector(3, 0))
                    .addSavedRegisters(scalar(1))
                    .build();

        assertThatThrownBy(() -> restoreOrganism(wellFormedOrganism().addCallStack(frame).build()))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining(String.valueOf(RegisterBank.STACK_SAVED_SNAPSHOT_SIZE));
    }

    @Test
    void restore_OrganismWithoutDataPointers_Rejected() {
        OrganismState organism = wellFormedOrganism().clearDataPointers().build();

        assertThatThrownBy(() -> restoreOrganism(organism))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining(String.valueOf(Config.NUM_DATA_POINTERS));
    }

    @Test
    void restore_OrganismWithTooFewDataPointers_Rejected() {
        OrganismState organism = wellFormedOrganism()
            .clearDataPointers()
            .addDataPointers(createVector(10, 10))
            .build();

        assertThatThrownBy(() -> restoreOrganism(organism))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining(String.valueOf(Config.NUM_DATA_POINTERS));
    }

    @Test
    void restore_ActiveDpIndexOutOfRange_Rejected() {
        OrganismState organism = wellFormedOrganism().setActiveDpIndex(5).build();

        assertThatThrownBy(() -> restoreOrganism(organism))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining("5");
    }

    @Test
    void restore_CellColumnsOfDifferentLength_Rejected() {
        CellDataColumns cells = CellDataColumns.newBuilder()
            .addFlatIndices(510)
            .addFlatIndices(511)
            .addMoleculeData(Config.TYPE_DATA | 42)
            .addOwnerIds(7)
            .build();

        assertThatThrownBy(() -> restoreSnapshot(snapshotWithCells(cells)))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining("Cell columns disagree");
    }

    @Test
    void restore_CellIndexBeyondWorld_Rejected() {
        // The configured world is 100 x 100, so 10000 is the first index outside it.
        CellDataColumns cells = CellDataColumns.newBuilder()
            .addFlatIndices(10_000)
            .addMoleculeData(Config.TYPE_DATA | 42)
            .addOwnerIds(7)
            .build();

        assertThatThrownBy(() -> restoreSnapshot(snapshotWithCells(cells)))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining("10000");
    }

    @Test
    void restore_NegativeCellIndex_Rejected() {
        CellDataColumns cells = CellDataColumns.newBuilder()
            .addFlatIndices(-1)
            .addMoleculeData(Config.TYPE_DATA | 42)
            .addOwnerIds(7)
            .build();

        assertThatThrownBy(() -> restoreSnapshot(snapshotWithCells(cells)))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining("-1");
    }

    @Test
    void restore_ConfiguredPluginWithoutState_Rejected() {
        TickData snapshot = snapshotWith(createOrganismState(1, 500));

        assertThatThrownBy(() -> SimulationRestorer.restore(
                    new ResumeCheckpoint(metadataWithLabelRewritePlugin(), snapshot), randomProvider, 1))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining(LabelRewritePlugin.class.getName())
                .hasMessageContaining("holds no state");
    }

    /**
     * The other side of the same problem: state is keyed by plugin class, so configuring one class
     * twice leaves the two instances with no way to get their own state back. Rejected where the
     * configuration is read, so the message names the configuration rather than the checkpoint.
     */
    @Test
    void restore_PluginConfiguredTwice_Rejected() {
        String twice = "[{ \"className\": \"" + LabelRewritePlugin.class.getName() + "\", \"options\": {} },"
                     + " { \"className\": \"" + LabelRewritePlugin.class.getName() + "\", \"options\": {} }]";
        SimulationMetadata metadata = createMinimalMetadata(twice);
        TickData snapshot = snapshotWith(createOrganismState(1, 500)).toBuilder()
            .addPluginStates(pluginState(LabelRewritePlugin.class))
            .build();

        assertThatThrownBy(() -> SimulationRestorer.restore(
                    new ResumeCheckpoint(metadata, snapshot), randomProvider, 1))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining(LabelRewritePlugin.class.getName())
                .hasMessageContaining("configured more than once");
    }

    /**
     * A procedure has one persistent register set. Two entries for the same label offer two, and
     * keeping either would pick one of them without saying so.
     */
    @Test
    void restore_DuplicatePersistentSnapshot_Rejected() {
        OrganismState organism = wellFormedOrganism()
            .setPersistentRegisterStore(org.evochora.datapipeline.api.contracts.PersistentRegisterStore.newBuilder()
                .addProcedureSnapshots(persistentSnapshot(4711))
                .addProcedureSnapshots(persistentSnapshot(4711))
                .build())
            .build();

        assertThatThrownBy(() -> restoreOrganism(organism))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining("4711")
                .hasMessageContaining("more than once");
    }

    /** A persistent register snapshot of the size this build expects, for the given procedure. */
    private org.evochora.datapipeline.api.contracts.ProcedureRegisterSnapshot persistentSnapshot(int labelHash) {
        org.evochora.datapipeline.api.contracts.ProcedureRegisterSnapshot.Builder snapshot =
                org.evochora.datapipeline.api.contracts.ProcedureRegisterSnapshot.newBuilder()
                    .setLabelHash(labelHash);
        for (int i = 0; i < RegisterBank.PERSISTENT_SNAPSHOT_SIZE; i++) {
            snapshot.addRegisters(scalar(0));
        }
        return snapshot.build();
    }

    @Test
    void restore_TruncatedRngState_Rejected() {
        TickData snapshot = snapshotWith(createOrganismState(1, 500)).toBuilder()
            .setRngState(validRngState().substring(0, 4))
            .build();

        assertThatThrownBy(() -> restoreSnapshot(snapshot))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining("RNG");
    }

    /**
     * Coordinate dimensions are checked by the builder, not by the restorer. The failure still has to
     * arrive as a resume failure naming the organism, or it reaches the service manager as an
     * unexpected runtime exception and is logged as a stack trace.
     */
    @Test
    void restore_DataPointerOfWrongDimension_Rejected() {
        OrganismState organism = wellFormedOrganism()
            .clearDataPointers()
            .addDataPointers(createVector(1, 1, 1))
            .addDataPointers(createVector(1, 1, 1))
            .build();

        assertThatThrownBy(() -> restoreOrganism(organism))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining("1");
    }

    @Test
    void restore_DuplicatePluginState_Rejected() {
        TickData snapshot = snapshotWith(createOrganismState(1, 500)).toBuilder()
            .addPluginStates(pluginState(LabelRewritePlugin.class))
            .addPluginStates(pluginState(LabelRewritePlugin.class))
            .build();

        assertThatThrownBy(() -> SimulationRestorer.restore(
                    new ResumeCheckpoint(metadataWithLabelRewritePlugin(), snapshot), randomProvider, 1))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining(LabelRewritePlugin.class.getName());
    }

    @Test
    void restore_PluginStateWithoutConfiguredPlugin_Rejected() {
        TickData snapshot = snapshotWith(createOrganismState(1, 500)).toBuilder()
            .addPluginStates(pluginState(LabelRewritePlugin.class))
            .build();

        assertThatThrownBy(() -> restoreSnapshot(snapshot))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining(LabelRewritePlugin.class.getName());
    }

    @Test
    void restore_UnsetRegisterValueInRegisterArray_Rejected() {
        OrganismState.Builder builder = wellFormedOrganism().clearRegisters();
        List<org.evochora.datapipeline.api.contracts.RegisterValue> registers =
                new ArrayList<>(ProtoTestUtils.buildFlatRegisters(null, null, null, null));
        registers.set(0, org.evochora.datapipeline.api.contracts.RegisterValue.getDefaultInstance());
        builder.addAllRegisters(registers);

        assertThatThrownBy(() -> restoreOrganism(builder.build()))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining("FLAT_REGISTER");
    }

    @Test
    void restore_UnsetRegisterValueInDataStack_Rejected() {
        OrganismState organism = wellFormedOrganism()
            .addDataStack(org.evochora.datapipeline.api.contracts.RegisterValue.getDefaultInstance())
            .build();

        assertThatThrownBy(() -> restoreOrganism(organism))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining("DATA_STACK");
    }

    @Test
    void restore_UnsetRegisterValueInCallFrame_Rejected() {
        org.evochora.datapipeline.api.contracts.ProcFrame.Builder frame =
                org.evochora.datapipeline.api.contracts.ProcFrame.newBuilder()
                    .setLabelHash(4715)
                    .setAbsoluteReturnIp(createVector(5, 0))
                    .setAbsoluteCallIp(createVector(3, 0));
        for (int i = 0; i < RegisterBank.STACK_SAVED_SNAPSHOT_SIZE; i++) {
            frame.addSavedRegisters(org.evochora.datapipeline.api.contracts.RegisterValue.getDefaultInstance());
        }

        assertThatThrownBy(() -> restoreOrganism(wellFormedOrganism().addCallStack(frame.build()).build()))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining("PROC_FRAME_SAVED");
    }

    @Test
    void restore_UnsetRegisterValueInPersistentStore_Rejected() {
        org.evochora.datapipeline.api.contracts.ProcedureRegisterSnapshot.Builder snapshot =
                org.evochora.datapipeline.api.contracts.ProcedureRegisterSnapshot.newBuilder()
                    .setLabelHash(4716);
        for (int i = 0; i < RegisterBank.PERSISTENT_SNAPSHOT_SIZE; i++) {
            snapshot.addRegisters(org.evochora.datapipeline.api.contracts.RegisterValue.getDefaultInstance());
        }

        OrganismState organism = wellFormedOrganism()
            .setPersistentRegisterStore(org.evochora.datapipeline.api.contracts.PersistentRegisterStore.newBuilder()
                .addProcedureSnapshots(snapshot.build())
                .build())
            .build();

        assertThatThrownBy(() -> restoreOrganism(organism))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining("PERSISTENT_STORE");
    }

    @Test
    void restore_FailedOrganismWithoutReason_Rejected() {
        OrganismState organism = wellFormedOrganism().setInstructionFailed(true).build();

        assertThatThrownBy(() -> restoreOrganism(organism))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void restore_DataStackBeyondLimit_Rejected() {
        OrganismState.Builder builder = wellFormedOrganism();
        for (int i = 0; i <= Config.DS_MAX_DEPTH; i++) {
            builder.addDataStack(scalar(i));
        }

        assertThatThrownBy(() -> restoreOrganism(builder.build()))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining(String.valueOf(Config.DS_MAX_DEPTH));
    }

    @Test
    void restore_LocationStackBeyondLimit_Rejected() {
        OrganismState.Builder builder = wellFormedOrganism();
        for (int i = 0; i <= Config.LOCATION_STACK_MAX_DEPTH; i++) {
            builder.addLocationStack(createVector(1, 1));
        }

        assertThatThrownBy(() -> restoreOrganism(builder.build()))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining(String.valueOf(Config.LOCATION_STACK_MAX_DEPTH));
    }

    @Test
    void restore_CallStackBeyondLimit_Rejected() {
        OrganismState.Builder builder = wellFormedOrganism();
        for (int i = 0; i <= Config.CALL_STACK_MAX_DEPTH; i++) {
            builder.addCallStack(org.evochora.datapipeline.api.contracts.ProcFrame.newBuilder()
                .setLabelHash(i)
                .setAbsoluteReturnIp(createVector(5, 0))
                .setAbsoluteCallIp(createVector(3, 0))
                .build());
        }

        assertThatThrownBy(() -> restoreOrganism(builder.build()))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining(String.valueOf(Config.CALL_STACK_MAX_DEPTH));
    }

    @Test
    void restore_UnknownParamType_Rejected() {
        org.evochora.datapipeline.api.contracts.ProgramArtifact program =
                org.evochora.datapipeline.api.contracts.ProgramArtifact.newBuilder()
                    .setProgramId("test-program")
                    .putProcNameToParamNames("MOD.PROC", org.evochora.datapipeline.api.contracts.ParameterNames.newBuilder()
                        .addParams(org.evochora.datapipeline.api.contracts.ParamInfo.newBuilder()
                            .setName("A")
                            .setTypeValue(99)
                            .build())
                        .build())
                    .build();

        SimulationMetadata metadata = createMinimalMetadata().toBuilder().addPrograms(program).build();
        TickData snapshot = snapshotWith(createOrganismState(1, 500));

        assertThatThrownBy(() -> SimulationRestorer.restore(
                    new ResumeCheckpoint(metadata, snapshot), randomProvider, 1))
                .isInstanceOf(ResumeException.class);
    }

    /**
     * A failure call stack is a copy of the call stack, so its frames carry an order: the frame the
     * organism was in when the instruction failed, then its callers. Restoring it reversed would
     * report the failure as having happened in the outermost procedure.
     */
    @Test
    void restore_FailureCallStack_KeepsFrameOrder() {
        org.evochora.datapipeline.api.contracts.ProcFrame outer = frame(100);
        org.evochora.datapipeline.api.contracts.ProcFrame inner = frame(200);

        SimulationRestorer.RestoredState state = restoreOrganism(wellFormedOrganism()
                .setInstructionFailed(true)
                .setFailureReason("failed inside a nested call")
                .addFailureCallStack(outer)
                .addFailureCallStack(inner)
                .build());

        Organism organism = state.simulation().getOrganisms().get(0);
        assertThat(organism.getFailureCallStack())
                .extracting(Organism.ProcFrame::labelHash)
                .as("frames must come back in the order they were written")
                .containsExactly(100, 200);
    }

    @Test
    void restore_UnknownTokenType_Rejected() {
        SimulationMetadata metadata = metadataWithToken(
                org.evochora.datapipeline.api.contracts.TokenInfo.newBuilder()
                    .setTokenText("HARVEST")
                    .setTokenType("NOT_A_TOKEN_KIND")
                    .setScope("global")
                    .build());

        assertThatThrownBy(() -> SimulationRestorer.restore(
                    new ResumeCheckpoint(metadata, snapshotWith(createOrganismState(1, 500))),
                    randomProvider, 1))
                .isInstanceOf(ResumeException.class)
                .hasMessageContaining("NOT_A_TOKEN_KIND");
    }

    private org.evochora.datapipeline.api.contracts.ProcFrame frame(int labelHash) {
        return org.evochora.datapipeline.api.contracts.ProcFrame.newBuilder()
            .setLabelHash(labelHash)
            .setAbsoluteReturnIp(createVector(5, 0))
            .setAbsoluteCallIp(createVector(3, 0))
            .build();
    }

    // ==================== Token metadata round trip ====================

    @Test
    void restore_TokenQualifiedName_Preserved() {
        SimulationMetadata metadata = metadataWithToken(
                org.evochora.datapipeline.api.contracts.TokenInfo.newBuilder()
                    .setTokenText("HARVEST")
                    .setTokenType("LABEL")
                    .setScope("global")
                    .setQualifiedName("ENERGY.HARVEST")
                    .build());

        SimulationRestorer.RestoredState state = SimulationRestorer.restore(
                new ResumeCheckpoint(metadata, snapshotWith(createOrganismState(1, 500))), randomProvider, 1);

        assertThat(restoredToken(state).qualifiedName()).isEqualTo("ENERGY.HARVEST");
    }

    /**
     * A token without a qualified name must come back as {@code null}, not as an empty string: the
     * record separates the two, and only {@code null} means "no qualification applies".
     */
    @Test
    void restore_TokenWithoutQualifiedName_StaysNull() {
        SimulationMetadata metadata = metadataWithToken(
                org.evochora.datapipeline.api.contracts.TokenInfo.newBuilder()
                    .setTokenText("%DR0")
                    .setTokenType("REGISTER")
                    .setScope("global")
                    .build());

        SimulationRestorer.RestoredState state = SimulationRestorer.restore(
                new ResumeCheckpoint(metadata, snapshotWith(createOrganismState(1, 500))), randomProvider, 1);

        assertThat(restoredToken(state).qualifiedName()).isNull();
    }

    // ==================== Helper Methods ====================

    /** A well-formed organism state; each rejection test breaks exactly one part of it. */
    private OrganismState.Builder wellFormedOrganism() {
        return OrganismState.newBuilder()
            .setOrganismId(1)
            .setBirthTick(0)
            .setEnergy(500)
            .setIp(createVector(10, 10))
            .setDv(createVector(1, 0))
            .setInitialPosition(createVector(5, 5))
            .addAllRegisters(ProtoTestUtils.buildFlatRegisters(null, null, null, null))
            .addDataPointers(createVector(10, 10))
            .addDataPointers(createVector(10, 10))
            .setIsDead(false);
    }

    private static org.evochora.datapipeline.api.contracts.RegisterValue scalar(int value) {
        return org.evochora.datapipeline.api.contracts.RegisterValue.newBuilder().setScalar(value).build();
    }

    private TickData snapshotWith(OrganismState organism) {
        return TickData.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setTickNumber(100)
            .setCaptureTimeMs(System.currentTimeMillis())
            .setTotalOrganismsCreated(10)
            .setCellColumns(CellDataColumns.newBuilder().build())
            .setRngState(validRngState())
            .addOrganisms(organism)
            .build();
    }

    private TickData snapshotWithCells(CellDataColumns cells) {
        return TickData.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setTickNumber(100)
            .setCaptureTimeMs(System.currentTimeMillis())
            .setTotalOrganismsCreated(10)
            .setCellColumns(cells)
            .setRngState(validRngState())
            .build();
    }

    private SimulationRestorer.RestoredState restoreOrganism(OrganismState organism) {
        return restoreSnapshot(snapshotWith(organism));
    }

    private SimulationRestorer.RestoredState restoreSnapshot(TickData snapshot) {
        return SimulationRestorer.restore(
                new ResumeCheckpoint(createMinimalMetadata(), snapshot), randomProvider, 1);
    }

    private org.evochora.datapipeline.api.contracts.PluginState pluginState(Class<?> pluginClass) {
        return org.evochora.datapipeline.api.contracts.PluginState.newBuilder()
            .setPluginClass(pluginClass.getName())
            .setStateBlob(ByteString.EMPTY)
            .build();
    }

    /** Metadata configuring one plugin, so that plugin state reconciliation can be exercised. */
    private SimulationMetadata metadataWithLabelRewritePlugin() {
        return createMinimalMetadata(
                "[{\"className\": \"" + LabelRewritePlugin.class.getName() + "\", \"options\": {}}]");
    }

    /** Metadata carrying one program whose token map holds exactly the given token. */
    private SimulationMetadata metadataWithToken(org.evochora.datapipeline.api.contracts.TokenInfo token) {
        org.evochora.datapipeline.api.contracts.ProgramArtifact program =
                org.evochora.datapipeline.api.contracts.ProgramArtifact.newBuilder()
                    .setProgramId("test-program")
                    .addTokenMap(org.evochora.datapipeline.api.contracts.TokenMapEntry.newBuilder()
                        .setSourceInfo(org.evochora.datapipeline.api.contracts.SourceInfo.newBuilder()
                            .setFileName("main.evo")
                            .setLineNumber(1)
                            .setColumnNumber(1)
                            .build())
                        .setTokenInfo(token)
                        .build())
                    .build();

        return createMinimalMetadata().toBuilder().addPrograms(program).build();
    }

    /** The single token of the single restored program artifact. */
    private org.evochora.compiler.api.TokenInfo restoredToken(SimulationRestorer.RestoredState state) {
        assertThat(state.programArtifacts()).hasSize(1);
        var tokenMap = state.programArtifacts().get("test-program").tokenMap();
        assertThat(tokenMap).hasSize(1);
        return tokenMap.values().iterator().next();
    }

    private SimulationMetadata createMinimalMetadata() {
        return createMinimalMetadata("[]");
    }

    private SimulationMetadata createMinimalMetadata(String pluginsJson) {
        String configJson = """
            {
              "runtime": {
                "organism": {
                  "max-energy": 32767,
                  "max-entropy": 8191,
                  "error-penalty-cost": 10
                },
                "thermodynamics": {
                  "default": {
                    "className": "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy",
                    "options": {
                      "base-energy": 1,
                      "base-entropy": 1
                    }
                  },
                  "overrides": {
                    "instructions": {},
                    "families": {}
                  }
                }
              }
            }
            """;

        // Build the full resolvedConfigJson with environment and runtime
        String fullConfigJson = TestMetadataHelper.builder()
            .shape(100, 100)
            .toroidal(true)
            .samplingInterval(1)
            .accumulatedDeltaInterval(5)
            .snapshotInterval(20)
            .chunkInterval(1)
            .pluginsJson(pluginsJson)
            .build();

        // Parse and merge with runtime config
        com.typesafe.config.Config parsedConfig = com.typesafe.config.ConfigFactory.parseString(fullConfigJson);
        com.typesafe.config.Config runtimeConfig = com.typesafe.config.ConfigFactory.parseString(configJson);
        String mergedJson = parsedConfig.withFallback(runtimeConfig)
            .root().render(com.typesafe.config.ConfigRenderOptions.concise());

        return SimulationMetadata.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setStartTimeMs(System.currentTimeMillis())
            .setInitialSeed(42)
            .setResolvedConfigJson(mergedJson)
            .build();
    }

    private TickData createSnapshot(long tick, long totalOrganisms) {
        return TickData.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setTickNumber(tick)
            .setCaptureTimeMs(System.currentTimeMillis())
            .setTotalOrganismsCreated(totalOrganisms)
            .setCellColumns(CellDataColumns.newBuilder().build())
            .setRngState(validRngState())
            .addOrganisms(createOrganismState(1, 500))
            .build();
    }

    private OrganismState createOrganismState(int id, int energy) {
        return OrganismState.newBuilder()
            .setOrganismId(id)
            .setBirthTick(0)
            .setEnergy(energy)
            .setIp(createVector(10, 10))
            .setDv(createVector(1, 0))
            .setInitialPosition(createVector(5, 5))
            .addAllRegisters(ProtoTestUtils.buildFlatRegisters(null, null, null, null))
            .addDataPointers(createVector(10, 10))
            .addDataPointers(createVector(10, 10))
            .setIsDead(false)
            .build();
    }

    private Vector createVector(int... components) {
        Vector.Builder builder = Vector.newBuilder();
        for (int c : components) {
            builder.addComponents(c);
        }
        return builder.build();
    }

    /**
     * The RNG state a snapshot carries. A snapshot written by a running simulation always holds one,
     * because the engine serializes {@code randomProvider.saveState()} on every snapshot tick; without
     * it the restored run could not continue the original random stream.
     */
    private static ByteString validRngState() {
        return ByteString.copyFrom(new SeededRandomProvider(42L).saveState());
    }

}
