package org.evochora.runtime.model;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Tests what an organism's direction of travel accepts and keeps.
 * <p>
 * Every reader of the DV relies on it naming exactly one axis, and the instructions that set it map
 * their operand before they do. These tests cover the guarantee itself: that the direction cannot
 * be set to something no instruction pointer could follow, and that it is never shared between two
 * organisms.
 */
@ExtendWith(LogWatchExtension.class)
class OrganismDirectionVectorTest {

    private Simulation simulation;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        simulation = SimulationTestUtils.createSimulation(new Environment(new int[]{32, 32}, true));
    }

    /**
     * A direction is kept as a copy, so that a caller holding on to the array it passed — a register,
     * or a vector it hands to a child as well — cannot turn the organism afterwards.
     */
    @Test
    @Tag("unit")
    void aDirectionIsKeptAsACopy() {
        Organism organism = Organism.create(simulation, new int[]{5, 5}, 100);
        int[] passed = new int[]{0, 1};

        organism.setDv(passed);
        passed[0] = 1;
        passed[1] = 0;

        assertThat(organism.getDv())
                .as("the organism keeps the direction it was given, not the caller's array")
                .isEqualTo(new int[]{0, 1});
    }

    /**
     * A direction naming two axes at once is one no instruction pointer could follow. No program can
     * produce it — every instruction maps its operand first — so it is a defect in the runtime, and
     * it is logged as one while the organism keeps the direction it had.
     */
    @Test
    @Tag("unit")
    @ExpectLog(level = LogLevel.ERROR, messagePattern = ".*names no single axis.*")
    void aDirectionThatNamesNoSingleAxisIsRejectedAndLogged() {
        Organism organism = Organism.create(simulation, new int[]{5, 5}, 100);
        int[] before = organism.getDv();

        organism.setDv(new int[]{1, 1});

        assertThat(organism.getDv())
                .as("the organism keeps the direction it had")
                .isEqualTo(before);
    }

    /**
     * The same holds for a direction that names no axis at all: an instruction answers that case
     * with the direction of the organism whose instruction is running, and nothing else may.
     */
    @Test
    @Tag("unit")
    @ExpectLog(level = LogLevel.ERROR, messagePattern = ".*names no single axis.*")
    void aDirectionWithoutAnAxisIsRejectedAndLogged() {
        Organism organism = Organism.create(simulation, new int[]{5, 5}, 100);
        int[] before = organism.getDv();

        organism.setDv(new int[]{0, 0});

        assertThat(organism.getDv()).isEqualTo(before);
    }
}
