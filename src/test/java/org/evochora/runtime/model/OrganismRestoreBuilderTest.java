package org.evochora.runtime.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.evochora.runtime.Config;
import org.evochora.runtime.isa.RegisterBank;
import org.evochora.junit.extensions.logging.ExpectLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.isa.Instruction;
import org.evochora.test.utils.SimulationTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link Organism.RestoreBuilder}.
 * <p>
 * Tests the builder pattern used for restoring organisms from serialized
 * checkpoint data during simulation resume.
 */
@ExtendWith(LogWatchExtension.class)
class OrganismRestoreBuilderTest {

    private Environment environment;
    private Simulation simulation;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        environment = new Environment(new int[]{96, 96}, true);
        simulation = SimulationTestUtils.createSimulation(environment);
    }

    // ==================== Basic Construction Tests ====================

    @Test
    @Tag("unit")
    void testRestoreBuilder_MinimalRequiredFields() {
        Organism org = Organism.restore(42, 1000L)
            .ip(new int[]{10, 20})
            .dv(new int[]{1, 0})
            .initialPosition(new int[]{5, 5})
            .build(simulation);

        assertThat(org.getId()).isEqualTo(42);
        assertThat(org.getBirthTick()).isEqualTo(1000L);
        assertThat(org.getIp()).isEqualTo(new int[]{10, 20});
        assertThat(org.getDv()).isEqualTo(new int[]{1, 0});
        assertThat(org.getEr()).isEqualTo(0); // default
        assertThat(org.getSr()).isEqualTo(0); // default
        assertThat(org.isDead()).isFalse(); // default
    }

    @Test
    @Tag("unit")
    void testRestoreBuilder_AllFields() {
        // Prepare complex state
        List<int[]> dps = Arrays.asList(new int[]{1, 2}, new int[]{3, 4});
        // Build flat register array in RegisterBank enum order: DR(8) + LR(4) + PDR(8) + FDR(8)
        Object[] regs = new Object[RegisterBank.TOTAL_REGISTER_COUNT];
        // DR0-DR7
        regs[RegisterBank.DR.slotOffset()] = 100; regs[RegisterBank.DR.slotOffset() + 1] = 200;
        regs[RegisterBank.DR.slotOffset() + 2] = 300; regs[RegisterBank.DR.slotOffset() + 3] = 400;
        regs[RegisterBank.DR.slotOffset() + 4] = 500; regs[RegisterBank.DR.slotOffset() + 5] = 600;
        regs[RegisterBank.DR.slotOffset() + 6] = 700; regs[RegisterBank.DR.slotOffset() + 7] = 800;
        // LR0-LR3
        regs[RegisterBank.LR.slotOffset()] = new int[]{0, 0}; regs[RegisterBank.LR.slotOffset() + 1] = new int[]{1, 1};
        regs[RegisterBank.LR.slotOffset() + 2] = new int[]{2, 2}; regs[RegisterBank.LR.slotOffset() + 3] = new int[]{3, 3};
        // PDR0-PDR3
        regs[RegisterBank.PDR.slotOffset()] = 10; regs[RegisterBank.PDR.slotOffset() + 1] = 20;
        regs[RegisterBank.PDR.slotOffset() + 2] = 30; regs[RegisterBank.PDR.slotOffset() + 3] = 40;
        // FDR0-FDR3
        regs[RegisterBank.FDR.slotOffset()] = 5; regs[RegisterBank.FDR.slotOffset() + 1] = 6;
        regs[RegisterBank.FDR.slotOffset() + 2] = 7; regs[RegisterBank.FDR.slotOffset() + 3] = 8;
        // Fill remaining slots with defaults
        for (RegisterBank bank : RegisterBank.values()) {
            for (int i = 0; i < bank.count; i++) {
                if (regs[bank.slotOffset() + i] == null) {
                    regs[bank.slotOffset() + i] = bank.isLocation ? new int[]{0, 0} : 0;
                }
            }
        }

        Deque<Object> dataStack = new ArrayDeque<>();
        dataStack.push(42);
        dataStack.push(new int[]{5, 5});

        Deque<int[]> locationStack = new ArrayDeque<>();
        locationStack.push(new int[]{7, 8});

        Deque<Organism.ProcFrame> callStack = new ArrayDeque<>();
        // Compact savedRegisters: every bank a CALL saves, in enum order, holding its default
        Object[] savedRegisters = defaultSnapshot(RegisterBank.allSavedOnCall(),
                RegisterBank.STACK_SAVED_SNAPSHOT_SIZE);
        int pdr = snapshotOffsetOf(RegisterBank.allSavedOnCall(), RegisterBank.PDR);
        int fdr = snapshotOffsetOf(RegisterBank.allSavedOnCall(), RegisterBank.FDR);
        savedRegisters[pdr] = 1; savedRegisters[pdr + 1] = 2;
        savedRegisters[fdr] = 3; savedRegisters[fdr + 1] = 4;
        callStack.push(new Organism.ProcFrame(
            0,
            new int[]{50, 50},
            new int[]{40, 40},
            savedRegisters
        ));

        Organism org = Organism.restore(99, 5000L)
            .parentId(50)
            .programId("test-program")
            .ip(new int[]{25, 30})
            .dv(new int[]{0, 1})
            .initialPosition(new int[]{20, 25})
            .energy(500)
            .entropy(100)
            .marker(7)
            .dataPointers(dps)
            .activeDpIndex(1)
            .registers(regs)
            .dataStack(dataStack)
            .locationStack(locationStack)
            .callStack(callStack)
            // Matches the frame's label hash: a call sets both from the same value
            .currentProcLabelHash(0)
            .dead(true)
            .failed(true, "Test failure")
            .build(simulation);

        // Verify all fields
        assertThat(org.getId()).isEqualTo(99);
        assertThat(org.getParentId()).isEqualTo(50);
        assertThat(org.getBirthTick()).isEqualTo(5000L);
        assertThat(org.getProgramId()).isEqualTo("test-program");
        assertThat(org.getIp()).isEqualTo(new int[]{25, 30});
        assertThat(org.getDv()).isEqualTo(new int[]{0, 1});
        assertThat(org.getEr()).isEqualTo(500);
        assertThat(org.getSr()).isEqualTo(100);
        assertThat(org.getMr()).isEqualTo(7);
        assertThat(org.getActiveDpIndex()).isEqualTo(1);
        assertThat(org.getDp(0)).isEqualTo(new int[]{1, 2});
        assertThat(org.getDp(1)).isEqualTo(new int[]{3, 4});
        assertThat(org.readOperand(0)).isEqualTo(100);
        assertThat(org.readOperand(RegisterBank.PDR.base)).isEqualTo(10);
        assertThat(org.readOperand(RegisterBank.FDR.base)).isEqualTo(5);
        assertThat(org.getDataStack()).hasSize(2);
        assertThat(org.getLocationStack()).hasSize(1);
        assertThat(org.getCallStack()).hasSize(1);
        assertThat(org.isDead()).isTrue();
        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).isEqualTo("Test failure");
    }

    // ==================== Validation Tests ====================

    @Test
    @Tag("unit")
    void testRestoreBuilder_NullSimulation_ThrowsException() {
        assertThatThrownBy(() ->
            Organism.restore(1, 0L)
                .ip(new int[]{0, 0})
                .dv(new int[]{1, 0})
                .build(null)
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Simulation cannot be null");
    }

    /**
     * A state the build cannot describe and a builder called wrongly are different failures, and a
     * caller translating one of them must not catch the other. A restorer turns the first into a
     * rejected checkpoint; doing that to the second would report a defect in the code as unusable
     * data, and the search would start in the wrong place.
     */
    @Test
    @Tag("unit")
    void testRestoreBuilder_InvalidStateAndWrongCall_AreDistinguishable() {
        assertThatThrownBy(() ->
            Organism.restore(1, 0L)
                .dv(new int[]{1, 0})
                .build(simulation)
        )
            .as("a state this build cannot describe")
            .isInstanceOf(Organism.InvalidRestoreState.class);

        assertThatThrownBy(() ->
            Organism.restore(1, 0L)
                .ip(new int[]{0, 0})
                .dv(new int[]{1, 0})
                .build(null)
        )
            .as("a builder called without a simulation")
            .isInstanceOf(IllegalStateException.class)
            .isNotInstanceOf(Organism.InvalidRestoreState.class);
    }

    /**
     * A location value enters a restored organism without passing the write gates every running
     * instruction goes through, so the builder holds it to the same two shapes: a position of one
     * component per world dimension, or none at all. Everything that reads a location register
     * afterwards relies on that.
     */
    @Test
    @Tag("unit")
    void testRestoreBuilder_LocationRegisterOfWrongLength_IsRejected() {
        Object[] registers = new Object[RegisterBank.TOTAL_REGISTER_COUNT];
        registers[RegisterBank.LR.slotOffset()] = new int[]{1, 2, 3};

        assertThatThrownBy(() ->
            Organism.restore(1, 0L)
                .ip(new int[]{0, 0})
                .dv(new int[]{1, 0})
                .initialPosition(new int[]{0, 0})
                .registers(registers)
                .build(simulation)
        )
            .isInstanceOf(Organism.InvalidRestoreState.class)
            .hasMessageContaining("must hold a position of 2 components or none");
    }

    /**
     * A procedure frame carries the proc-local and formal registers of its caller, and a RET copies
     * them back into the registers wholesale. A location value stored there is held to the same two
     * shapes, or it would become a live location register that no reader can use.
     */
    @Test
    @Tag("unit")
    void testRestoreBuilder_SavedLocationRegisterOfWrongLength_IsRejected() {
        Object[] savedRegisters = defaultSnapshot(RegisterBank.allSavedOnCall(),
                RegisterBank.STACK_SAVED_SNAPSHOT_SIZE);
        savedRegisters[snapshotOffsetOf(RegisterBank.allSavedOnCall(), RegisterBank.PLR)] = new int[]{1, 2, 3};

        Deque<Organism.ProcFrame> callStack = new ArrayDeque<>();
        callStack.push(new Organism.ProcFrame(1, new int[]{0, 0}, new int[]{0, 0}, savedRegisters));

        assertThatThrownBy(() ->
            Organism.restore(1, 0L)
                .ip(new int[]{0, 0})
                .dv(new int[]{1, 0})
                .initialPosition(new int[]{0, 0})
                .callStack(callStack)
                .build(simulation)
        )
            .isInstanceOf(Organism.InvalidRestoreState.class)
            .hasMessageContaining("%PLR0")
            .hasMessageContaining("neither a position of 2 components nor none");
    }

    /**
     * The same for the static registers, which a procedure switch swaps in from their per-procedure
     * backing store rather than from a frame.
     */
    @Test
    @Tag("unit")
    void testRestoreBuilder_PersistentLocationRegisterOfWrongLength_IsRejected() {
        Object[] snapshot = defaultSnapshot(RegisterBank.allPersistent(),
                RegisterBank.PERSISTENT_SNAPSHOT_SIZE);
        snapshot[snapshotOffsetOf(RegisterBank.allPersistent(), RegisterBank.SLR)] = new int[]{4};

        Map<Integer, Object[]> persistentState = new HashMap<>();
        persistentState.put(0, snapshot);

        assertThatThrownBy(() ->
            Organism.restore(1, 0L)
                .ip(new int[]{0, 0})
                .dv(new int[]{1, 0})
                .initialPosition(new int[]{0, 0})
                .persistentRegisterState(persistentState)
                .build(simulation)
        )
            .isInstanceOf(Organism.InvalidRestoreState.class)
            .hasMessageContaining("%SLR0")
            .hasMessageContaining("neither a position of 2 components nor none");
    }

    /** Builds a snapshot of the given banks that holds the default of every register. */
    private static Object[] defaultSnapshot(List<RegisterBank> banks, int size) {
        Object[] snapshot = new Object[size];
        int offset = 0;
        for (RegisterBank bank : banks) {
            for (int i = 0; i < bank.count; i++) {
                snapshot[offset + i] = bank.isLocation ? LocationValue.NONE : 0;
            }
            offset += bank.count;
        }
        return snapshot;
    }

    /** Returns where the first register of one bank sits in a snapshot of the given banks. */
    private static int snapshotOffsetOf(List<RegisterBank> banks, RegisterBank wanted) {
        int offset = 0;
        for (RegisterBank bank : banks) {
            if (bank == wanted) {
                return offset;
            }
            offset += bank.count;
        }
        throw new IllegalArgumentException(wanted + " is not among " + banks);
    }

    /**
     * The same for an entry of the location stack, which is restored the same way.
     */
    @Test
    @Tag("unit")
    void testRestoreBuilder_LocationStackEntryOfWrongLength_IsRejected() {
        java.util.Deque<int[]> locationStack = new java.util.ArrayDeque<>();
        locationStack.addLast(new int[]{7});

        assertThatThrownBy(() ->
            Organism.restore(1, 0L)
                .ip(new int[]{0, 0})
                .dv(new int[]{1, 0})
                .initialPosition(new int[]{0, 0})
                .locationStack(locationStack)
                .build(simulation)
        )
            .isInstanceOf(Organism.InvalidRestoreState.class)
            .hasMessageContaining("Location stack entry must hold a position of 2 components or none");
    }

    /**
     * A register the state left unset is filled with the state by the constructor, so it passes.
     */
    @Test
    @Tag("unit")
    void testRestoreBuilder_LocationRegisterLeftUnset_IsAccepted() {
        Object[] registers = new Object[RegisterBank.TOTAL_REGISTER_COUNT];
        registers[RegisterBank.LR.slotOffset()] = LocationValue.NONE;

        Organism restored = Organism.restore(1, 0L)
                .ip(new int[]{0, 0})
                .dv(new int[]{1, 0})
                .initialPosition(new int[]{0, 0})
                .registers(registers)
                .build(simulation);

        assertThat(LocationValue.isNone((int[]) restored.readOperand(RegisterBank.LR.base))).isTrue();
    }

    @Test
    @Tag("unit")
    void testRestoreBuilder_MissingIp_ThrowsException() {
        assertThatThrownBy(() ->
            Organism.restore(1, 0L)
                .dv(new int[]{1, 0})
                .build(simulation)
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("IP must be set");
    }

    /**
     * Every runtime path that sets a direction maps it to a unit vector, so a stored direction that
     * is not one describes an organism no run of this build could have produced. Restoring it would
     * advance an instruction pointer in steps of more than one cell, or in a direction the state
     * does not name at all.
     */
    @Test
    @Tag("unit")
    void testRestoreBuilder_NonUnitDv_ThrowsException() {
        assertThatThrownBy(() ->
            Organism.restore(1, 0L)
                .ip(new int[]{0, 0})
                .dv(new int[]{1, 1})
                .initialPosition(new int[]{0, 0})
                .build(simulation)
        )
            .as("two axes at once is no direction of travel")
            .isInstanceOf(Organism.InvalidRestoreState.class)
            .hasMessageContaining("unit vector");

        assertThatThrownBy(() ->
            Organism.restore(1, 0L)
                .ip(new int[]{0, 0})
                .dv(new int[]{2, 0})
                .initialPosition(new int[]{0, 0})
                .build(simulation)
        )
            .as("a step of two cells is no direction of travel")
            .isInstanceOf(Organism.InvalidRestoreState.class)
            .hasMessageContaining("unit vector");
    }

    @Test
    @Tag("unit")
    void testRestoreBuilder_MissingDv_ThrowsException() {
        assertThatThrownBy(() ->
            Organism.restore(1, 0L)
                .ip(new int[]{0, 0})
                .build(simulation)
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DV must be set");
    }

    @Test
    @Tag("unit")
    void testRestoreBuilder_DimensionMismatch_ThrowsException() {
        assertThatThrownBy(() ->
            Organism.restore(1, 0L)
                .ip(new int[]{0, 0, 0})  // 3D
                .dv(new int[]{1, 0})      // 2D
                .build(simulation)
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("same dimensions");
    }

    @Test
    @Tag("unit")
    @ExpectLog(level = LogLevel.WARN, messagePattern = ".*negative energy.*")
    void testRestoreBuilder_NegativeEnergy_WarnsButCreates() {
        Organism org = Organism.restore(1, 0L)
            .ip(new int[]{0, 0})
            .dv(new int[]{1, 0})
            .initialPosition(new int[]{0, 0})
            .energy(-10)
            .build(simulation);

        assertThat(org).isNotNull();
        assertThat(org.getEr()).isEqualTo(-10);
    }

    @Test
    @Tag("unit")
    @ExpectLog(level = LogLevel.WARN, messagePattern = ".*negative entropy.*")
    void testRestoreBuilder_NegativeEntropy_WarnsButCreates() {
        Organism org = Organism.restore(1, 0L)
            .ip(new int[]{0, 0})
            .dv(new int[]{1, 0})
            .initialPosition(new int[]{0, 0})
            .entropy(-5)
            .build(simulation);

        assertThat(org).isNotNull();
        assertThat(org.getSr()).isEqualTo(-5);
    }

    // ==================== Immutability Tests ====================

    @Test
    @Tag("unit")
    void testRestoreBuilder_IpIsCopied() {
        int[] originalIp = new int[]{10, 20};
        Organism org = Organism.restore(1, 0L)
            .ip(originalIp)
            .dv(new int[]{1, 0})
            .initialPosition(new int[]{10, 20})
            .build(simulation);

        // Modify original array
        originalIp[0] = 999;

        // Organism's IP should be unchanged
        assertThat(org.getIp()).isEqualTo(new int[]{10, 20});
    }

    @Test
    @Tag("unit")
    void testRestoreBuilder_DpsAreCopied() {
        List<int[]> originalDps = new ArrayList<>();
        originalDps.add(new int[]{1, 2});
        originalDps.add(new int[]{3, 4});

        Organism org = Organism.restore(1, 0L)
            .ip(new int[]{0, 0})
            .dv(new int[]{1, 0})
            .initialPosition(new int[]{0, 0})
            .dataPointers(originalDps)
            .build(simulation);

        // Modify original
        originalDps.get(0)[0] = 999;

        // Organism's DPs should be unchanged
        assertThat(org.getDp(0)).isEqualTo(new int[]{1, 2});
    }

    // ==================== Derived Fields Tests ====================

    @Test
    @Tag("unit")
    void testRestoreBuilder_InitialPositionPreserved() {
        Organism org = Organism.restore(1, 0L)
            .ip(new int[]{42, 43})
            .dv(new int[]{1, 0})
            .initialPosition(new int[]{10, 20})
            .build(simulation);

        // initialPosition should be the birth position, NOT the current IP
        assertThat(org.getInitialPosition()).isEqualTo(new int[]{10, 20});
        assertThat(org.getIp()).isEqualTo(new int[]{42, 43});
    }

    @Test
    @Tag("unit")
    void testRestoreBuilder_RandomIsInitialized() {
        Organism org = Organism.restore(1, 0L)
            .ip(new int[]{0, 0})
            .dv(new int[]{1, 0})
            .initialPosition(new int[]{0, 0})
            .build(simulation);

        // Random should be initialized (not null)
        assertThat(org.getRandom()).isNotNull();
    }

    @Test
    @Tag("unit")
    void testRestoreBuilder_MaxEnergyFromConfig() {
        Organism org = Organism.restore(1, 0L)
            .ip(new int[]{0, 0})
            .dv(new int[]{1, 0})
            .initialPosition(new int[]{0, 0})
            .build(simulation);

        // Max energy comes from simulation config
        assertThat(org.getMaxEnergy()).isEqualTo(32767); // SimulationTestUtils default
    }

    // ==================== State Invariants ====================
    //
    // The builder reconstructs an organism from persisted state. Anything it silently repairs
    // produces an organism that differs from the one the checkpoint described, which is exactly what
    // a resume must not do. Values that are not set at all still receive defaults — completeness is
    // enforced by the restorer, which knows the data came from a checkpoint.

    /**
     * The builder restores a stack at whatever depth it is given, the instruction set's limit
     * included: cutting it would silently drop values or return targets and continue from a state
     * the original run never had. Whether the depth deserves a warning is the restorer's business,
     * which knows the data came from a checkpoint.
     */
    @Test
    @Tag("unit")
    void testRestoreBuilder_LocationStackBeyondLimit_RestoredAsGiven() {
        Deque<int[]> oversizedStack = new ArrayDeque<>();
        for (int i = 0; i <= Config.LOCATION_STACK_MAX_DEPTH; i++) {
            oversizedStack.addLast(new int[]{i, i});
        }

        Organism organism = Organism.restore(1, 0L)
                .ip(new int[]{0, 0})
                .dv(new int[]{1, 0})
                .initialPosition(new int[]{0, 0})
                .locationStack(oversizedStack)
                .build(simulation);

        assertThat(organism.getLocationStack()).hasSize(Config.LOCATION_STACK_MAX_DEPTH + 1);
    }

    @Test
    @Tag("unit")
    void testRestoreBuilder_DataStackBeyondLimit_RestoredAsGiven() {
        Deque<Object> oversizedStack = new ArrayDeque<>();
        for (int i = 0; i <= Config.DS_MAX_DEPTH; i++) {
            oversizedStack.addLast(i);
        }

        Organism organism = Organism.restore(1, 0L)
                .ip(new int[]{0, 0})
                .dv(new int[]{1, 0})
                .initialPosition(new int[]{0, 0})
                .dataStack(oversizedStack)
                .build(simulation);

        assertThat(organism.getDataStack()).hasSize(Config.DS_MAX_DEPTH + 1);
    }

    @Test
    @Tag("unit")
    void testRestoreBuilder_CallStackBeyondLimit_RestoredAsGiven() {
        Deque<Organism.ProcFrame> oversizedStack = new ArrayDeque<>();
        for (int i = 0; i <= Config.CALL_STACK_MAX_DEPTH; i++) {
            oversizedStack.addLast(new Organism.ProcFrame(
                    i, new int[]{0, 0}, new int[]{0, 0}, null));
        }

        Organism organism = Organism.restore(1, 0L)
                .ip(new int[]{0, 0})
                .dv(new int[]{1, 0})
                .initialPosition(new int[]{0, 0})
                .callStack(oversizedStack)
                .build(simulation);

        assertThat(organism.getCallStack()).hasSize(Config.CALL_STACK_MAX_DEPTH + 1);
    }

    /**
     * A register array of the wrong length cannot be mapped onto the banks: padding it or cutting it
     * short would put values into slots that belong to a different bank, or invent values that were
     * never saved.
     */
    @Test
    @Tag("unit")
    void testRestoreBuilder_WrongRegisterCount_ThrowsException() {
        assertThatThrownBy(() ->
            Organism.restore(1, 0L)
                .ip(new int[]{0, 0})
                .dv(new int[]{1, 0})
                .initialPosition(new int[]{0, 0})
                .registers(new Object[RegisterBank.TOTAL_REGISTER_COUNT - 1])
                .build(simulation)
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(String.valueOf(RegisterBank.TOTAL_REGISTER_COUNT));
    }

    @Test
    @Tag("unit")
    void testRestoreBuilder_WrongDataPointerCount_ThrowsException() {
        assertThatThrownBy(() ->
            Organism.restore(1, 0L)
                .ip(new int[]{0, 0})
                .dv(new int[]{1, 0})
                .initialPosition(new int[]{0, 0})
                .dataPointers(List.of(new int[]{1, 1}))
                .build(simulation)
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(String.valueOf(Config.NUM_DATA_POINTERS));
    }

    @Test
    @Tag("unit")
    void testRestoreBuilder_DataPointerDimensionMismatch_ThrowsException() {
        assertThatThrownBy(() ->
            Organism.restore(1, 0L)
                .ip(new int[]{0, 0})
                .dv(new int[]{1, 0})
                .initialPosition(new int[]{0, 0})
                .dataPointers(List.of(new int[]{1, 1, 1}, new int[]{2, 2, 2}))
                .build(simulation)
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("dimension");
    }

    /**
     * An index is rejected even when no data pointers were supplied: it then cannot reference any
     * pointer at all. Only the untouched default of zero passes, so restoring a minimal organism
     * stays possible.
     */
    @Test
    @Tag("unit")
    void testRestoreBuilder_ActiveDpIndexWithoutDataPointers_ThrowsException() {
        assertThatThrownBy(() ->
            Organism.restore(1, 0L)
                .ip(new int[]{0, 0})
                .dv(new int[]{1, 0})
                .initialPosition(new int[]{0, 0})
                .activeDpIndex(1)
                .build(simulation)
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Active data pointer index")
            .hasMessageContaining("1");
    }

    @Test
    @Tag("unit")
    void testRestoreBuilder_ActiveDpIndexOutOfRange_ThrowsException() {
        assertThatThrownBy(() ->
            Organism.restore(1, 0L)
                .ip(new int[]{0, 0})
                .dv(new int[]{1, 0})
                .initialPosition(new int[]{0, 0})
                .dataPointers(List.of(new int[]{1, 1}, new int[]{2, 2}))
                .activeDpIndex(Config.NUM_DATA_POINTERS)
                .build(simulation)
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(String.valueOf(Config.NUM_DATA_POINTERS));
    }

    @Test
    @Tag("unit")
    void testRestoreBuilder_LocationStackUnchangedWhenWithinLimit() {
        Deque<int[]> normalStack = new ArrayDeque<>();
        normalStack.addLast(new int[]{1, 1});
        normalStack.addLast(new int[]{2, 2});

        Organism org = Organism.restore(1, 0L)
            .ip(new int[]{0, 0})
            .dv(new int[]{1, 0})
            .initialPosition(new int[]{0, 0})
            .locationStack(normalStack)
            .build(simulation);

        assertThat(org.getLocationStack().size()).isEqualTo(2);
    }

    // ==================== Round-Trip Test ====================

    @Test
    @Tag("unit")
    void testRestoreBuilder_RoundTrip() {
        // Create original organism
        Organism original = Organism.create(simulation, new int[]{10, 10}, 500);
        original.setParentId(5);
        original.setBirthTick(1000L);
        original.setProgramId("test-prog");
        original.setDv(new int[]{0, 1});
        original.addSr(50);
        original.setMr(3);
        original.writeOperand(RegisterBank.DR.base, 42);                        // DR0
        original.writeLocationOperand(RegisterBank.LR.base, new int[]{3, 4});   // LR0
        original.writeOperand(RegisterBank.PDR.base, 100);                      // PDR0
        original.writeLocationOperand(RegisterBank.PLR.base, new int[]{5, 6});  // PLR0
        original.writeOperand(RegisterBank.FDR.base, 200);                      // FDR0
        original.writeLocationOperand(RegisterBank.FLR.base, new int[]{1, 2});  // FLR0
        original.writeOperand(RegisterBank.SDR.base, 300);                      // SDR0
        original.writeLocationOperand(RegisterBank.SLR.base, new int[]{7, 8});  // SLR0

        // Restore using builder
        Organism restored = Organism.restore(original.getId(), original.getBirthTick())
            .parentId(original.getParentId())
            .programId(original.getProgramId())
            .ip(original.getIp())
            .dv(original.getDv())
            .initialPosition(original.getInitialPosition())
            .energy(original.getEr())
            .entropy(original.getSr())
            .marker(original.getMr())
            .dataPointers(original.getDps())
            .activeDpIndex(original.getActiveDpIndex())
            .registers(original.getRegisters().clone())
            .dataStack(original.getDataStack())
            .locationStack(original.getLocationStack())
            .callStack(original.getCallStack())
            .dead(original.isDead())
            .failed(original.isInstructionFailed(), original.getFailureReason())
            .build(simulation);

        // Verify all state matches
        assertThat(restored.getId()).isEqualTo(original.getId());
        assertThat(restored.getParentId()).isEqualTo(original.getParentId());
        assertThat(restored.getBirthTick()).isEqualTo(original.getBirthTick());
        assertThat(restored.getProgramId()).isEqualTo(original.getProgramId());
        assertThat(restored.getIp()).isEqualTo(original.getIp());
        assertThat(restored.getDv()).isEqualTo(original.getDv());
        assertThat(restored.getEr()).isEqualTo(original.getEr());
        assertThat(restored.getSr()).isEqualTo(original.getSr());
        assertThat(restored.getMr()).isEqualTo(original.getMr());
        assertThat(restored.readOperand(RegisterBank.DR.base)).isEqualTo(original.readOperand(RegisterBank.DR.base));
        assertThat(restored.readOperand(RegisterBank.LR.base)).isEqualTo(original.readOperand(RegisterBank.LR.base));
        assertThat(restored.readOperand(RegisterBank.PDR.base)).isEqualTo(original.readOperand(RegisterBank.PDR.base));
        assertThat(restored.readOperand(RegisterBank.PLR.base)).isEqualTo(original.readOperand(RegisterBank.PLR.base));
        assertThat(restored.readOperand(RegisterBank.FDR.base)).isEqualTo(original.readOperand(RegisterBank.FDR.base));
        assertThat(restored.readOperand(RegisterBank.FLR.base)).isEqualTo(original.readOperand(RegisterBank.FLR.base));
        assertThat(restored.readOperand(RegisterBank.SDR.base)).isEqualTo(original.readOperand(RegisterBank.SDR.base));
        assertThat(restored.readOperand(RegisterBank.SLR.base)).isEqualTo(original.readOperand(RegisterBank.SLR.base));
        assertThat(restored.getCurrentProcLabelHash()).isEqualTo(original.getCurrentProcLabelHash());
    }
}
