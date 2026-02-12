package org.evochora.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.test.utils.SimulationTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link VirtualMachine}, focusing on the {@code peekNextInstruction} method.
 */
@Tag("unit")
class VirtualMachineTest {

    private Environment environment;
    private Simulation sim;
    private VirtualMachine vm;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        environment = new Environment(new int[]{100, 100}, true);
        sim = SimulationTestUtils.createSimulation(environment);
        vm = sim.getVirtualMachine();
    }

    @Test
    void peekNextInstruction_validCode_returnsData() {
        Organism org = Organism.create(sim, new int[]{10, 10}, 1000, sim.getLogger());
        sim.addOrganism(org);

        // Place SETI %DR0, DATA:42 at organism IP
        int setiOpcode = Instruction.getInstructionIdByName("SETI");
        environment.setMolecule(new Molecule(Config.TYPE_CODE, setiOpcode), org.getIp());
        int[] argPos1 = org.getNextInstructionPosition(org.getIp(), org.getDv(), environment);
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 0), argPos1); // %DR0
        int[] argPos2 = org.getNextInstructionPosition(argPos1, org.getDv(), environment);
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 42), argPos2); // immediate 42

        Organism.InstructionExecutionData data = vm.peekNextInstruction(org);

        assertThat(data).isNotNull();
        assertThat(data.opcodeId()).isEqualTo(setiOpcode);
        assertThat(data.rawArguments()).hasSize(2);
        assertThat(data.energyCost()).isZero();
        assertThat(data.entropyDelta()).isZero();
    }

    @Test
    void peekNextInstruction_deadOrganism_returnsNull() {
        Organism org = Organism.create(sim, new int[]{10, 10}, 1000, sim.getLogger());
        sim.addOrganism(org);

        int setiOpcode = Instruction.getInstructionIdByName("SETI");
        environment.setMolecule(new Molecule(Config.TYPE_CODE, setiOpcode), org.getIp());

        org.kill("test");

        assertThat(vm.peekNextInstruction(org)).isNull();
    }

    @Test
    void peekNextInstruction_emptyMolecule_returnsNull() {
        Organism org = Organism.create(sim, new int[]{10, 10}, 1000, sim.getLogger());
        sim.addOrganism(org);

        // IP points to empty cell (default)
        assertThat(vm.peekNextInstruction(org)).isNull();
    }

    @Test
    void peekNextInstruction_nonCodeMolecule_returnsNull() {
        Organism org = Organism.create(sim, new int[]{10, 10}, 1000, sim.getLogger());
        sim.addOrganism(org);

        // Place a DATA molecule (not CODE) at IP
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 99), org.getIp());

        if (Config.STRICT_TYPING) {
            assertThat(vm.peekNextInstruction(org)).isNull();
        }
    }

    @Test
    void peekNextInstruction_unknownOpcode_returnsNull() {
        Organism org = Organism.create(sim, new int[]{10, 10}, 1000, sim.getLogger());
        sim.addOrganism(org);

        // Place CODE molecule with an opcode that has no planner (very high value)
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 0x3FFFF), org.getIp());

        assertThat(vm.peekNextInstruction(org)).isNull();
    }

    @Test
    void peekNextInstruction_capturesRegisterValues() {
        Organism org = Organism.create(sim, new int[]{10, 10}, 1000, sim.getLogger());
        sim.addOrganism(org);
        org.setDr(0, 777);

        // Place SETI %DR0, DATA:42 — DR0 is a REGISTER argument
        int setiOpcode = Instruction.getInstructionIdByName("SETI");
        environment.setMolecule(new Molecule(Config.TYPE_CODE, setiOpcode), org.getIp());
        int[] argPos1 = org.getNextInstructionPosition(org.getIp(), org.getDv(), environment);
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 0), argPos1); // %DR0
        int[] argPos2 = org.getNextInstructionPosition(argPos1, org.getDv(), environment);
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 42), argPos2);

        Organism.InstructionExecutionData data = vm.peekNextInstruction(org);

        assertThat(data).isNotNull();
        assertThat(data.registerValuesBefore()).containsKey(0);
        assertThat(data.registerValuesBefore().get(0)).isEqualTo(777);
    }
}
