package org.evochora.runtime.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;

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
        environment = new Environment(new int[]{100, 100}, true);
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
        List<Object> drs = new ArrayList<>(Arrays.asList(100, 200, 300, 400, 500, 600, 700, 800));
        List<Object> prs = new ArrayList<>(Arrays.asList(10, 20, 30, 40));
        List<Object> fprs = new ArrayList<>(Arrays.asList(5, 6, 7, 8));
        List<Object> lrs = new ArrayList<>(Arrays.asList(
            new int[]{0, 0}, new int[]{1, 1}, new int[]{2, 2}, new int[]{3, 3}
        ));

        Deque<Object> dataStack = new ArrayDeque<>();
        dataStack.push(42);
        dataStack.push(new int[]{5, 5});

        Deque<int[]> locationStack = new ArrayDeque<>();
        locationStack.push(new int[]{7, 8});

        Deque<Organism.ProcFrame> callStack = new ArrayDeque<>();
        callStack.push(new Organism.ProcFrame(
            "testProc",
            new int[]{50, 50},
            new int[]{40, 40},
            new Object[]{1, 2},
            new Object[]{3, 4},
            Map.of(0, 1)
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
            .dataRegisters(drs)
            .procRegisters(prs)
            .formalParamRegisters(fprs)
            .locationRegisters(lrs)
            .dataStack(dataStack)
            .locationStack(locationStack)
            .callStack(callStack)
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
        assertThat(org.getDrs()).hasSize(8);
        assertThat(org.getDr(0)).isEqualTo(100);
        assertThat(org.getPrs()).hasSize(4);
        assertThat(org.getPr(0)).isEqualTo(10);
        assertThat(org.getFprs()).hasSize(4);
        assertThat(org.getFpr(0)).isEqualTo(5);
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

    // ==================== Round-Trip Test ====================

    @Test
    @Tag("unit")
    void testRestoreBuilder_RoundTrip() {
        // Create original organism
        Organism original = Organism.create(simulation, new int[]{10, 10}, 500, simulation.getLogger());
        original.setParentId(5);
        original.setBirthTick(1000L);
        original.setProgramId("test-prog");
        original.setDv(new int[]{0, 1});
        original.addSr(50);
        original.setMr(3);
        original.setDr(0, 42);
        original.setPr(0, 100);
        original.setFpr(0, new int[]{1, 2});

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
            .dataRegisters(original.getDrs())
            .procRegisters(original.getPrs())
            .formalParamRegisters(original.getFprs())
            .locationRegisters(original.getLrs())
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
        assertThat(restored.getDr(0)).isEqualTo(original.getDr(0));
        assertThat(restored.getPr(0)).isEqualTo(original.getPr(0));
        assertThat(restored.getFpr(0)).isEqualTo(original.getFpr(0));
    }
}
