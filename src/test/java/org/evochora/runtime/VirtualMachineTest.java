package org.evochora.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.instructions.ConditionalJumpInstruction;
import org.evochora.runtime.isa.instructions.ConditionalSkipInstruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.test.utils.SimulationTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link VirtualMachine}: the {@code peekNextInstruction} method, and which failed
 * instructions it executes.
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
        environment = new Environment(new int[]{96, 96}, true);
        sim = SimulationTestUtils.createSimulation(environment);
        vm = sim.getVirtualMachine();
    }

    @Test
    void peekNextInstruction_validCode_returnsData() {
        Organism org = Organism.create(sim, new int[]{10, 10}, 1000);
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
        Organism org = Organism.create(sim, new int[]{10, 10}, 1000);
        sim.addOrganism(org);

        int setiOpcode = Instruction.getInstructionIdByName("SETI");
        environment.setMolecule(new Molecule(Config.TYPE_CODE, setiOpcode), org.getIp());

        org.kill("test");

        assertThat(vm.peekNextInstruction(org)).isNull();
    }

    @Test
    void peekNextInstruction_emptyMolecule_returnsNull() {
        Organism org = Organism.create(sim, new int[]{10, 10}, 1000);
        sim.addOrganism(org);

        // IP points to empty cell (default)
        assertThat(vm.peekNextInstruction(org)).isNull();
    }

    @Test
    void peekNextInstruction_nonCodeMolecule_returnsNull() {
        Organism org = Organism.create(sim, new int[]{10, 10}, 1000);
        sim.addOrganism(org);

        // Place a DATA molecule (not CODE) at IP
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 99), org.getIp());

        assertThat(vm.peekNextInstruction(org)).isNull();
    }

    @Test
    void peekNextInstruction_unknownOpcode_returnsNull() {
        Organism org = Organism.create(sim, new int[]{10, 10}, 1000);
        sim.addOrganism(org);

        // Place CODE molecule with an opcode that has no planner (very high value)
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 0x3FFFF), org.getIp());

        assertThat(vm.peekNextInstruction(org)).isNull();
    }

    @Test
    void peekNextInstruction_capturesRegisterValues() {
        Organism org = Organism.create(sim, new int[]{10, 10}, 1000);
        sim.addOrganism(org);
        org.writeOperand(0, 777);

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

    // ==================== Execution of an instruction that failed while planned ====================

    /**
     * The instructions that are executed although they failed while they were planned are the
     * conditionals, exactly: a conditional and its negation decide "does not hold" and "holds"
     * on a test that cannot be evaluated, and no other instruction has anything to decide.
     */
    @Test
    void exactlyTheConditionalsDecideOnFailure() {
        for (var entry : Instruction.getAllInstructions().entrySet()) {
            Class<? extends Instruction> kind = Instruction.getInstructionClassById(entry.getKey());
            boolean conditional = kind == ConditionalSkipInstruction.class || kind == ConditionalJumpInstruction.class;
            assertThat(Instruction.decidesOnFailure(entry.getKey()))
                    .as("%s decides on failure", entry.getValue())
                    .isEqualTo(conditional);
        }
        assertThat(Instruction.decidesOnFailure(-1)).isFalse();
        assertThat(Instruction.decidesOnFailure(0x3FFFF)).isFalse();
    }

    /**
     * An instruction that is no conditional and failed while it was planned is not executed: a
     * SETI whose register operand names no register writes nothing, and pays for the failure.
     */
    @Test
    void aFailedInstructionThatDecidesNothingIsNotExecuted() {
        Organism org = Organism.create(sim, new int[]{10, 10}, 1000);
        sim.addOrganism(org);
        org.writeOperand(0, new Molecule(Config.TYPE_DATA, 7).toInt());
        int setiOpcode = Instruction.getInstructionIdByName("SETI");
        environment.setMolecule(new Molecule(Config.TYPE_CODE, setiOpcode), org.getIp());
        int[] argPos1 = org.getNextInstructionPosition(org.getIp(), org.getDv(), environment);
        environment.setMolecule(new Molecule(Config.TYPE_DATA, -1), argPos1);
        int[] argPos2 = org.getNextInstructionPosition(argPos1, org.getDv(), environment);
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 42), argPos2);
        int penalty = sim.getOrganismConfig().getInt("error-penalty-cost");

        sim.tick();

        assertThat(org.getFailureReason()).isEqualTo("Invalid register ID: -1");
        assertThat(org.readOperand(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 7).toInt());
        assertThat(org.getEr()).isStrictlyBetween(1000 - 2 * penalty, 1000 - penalty + 1);
    }
}
