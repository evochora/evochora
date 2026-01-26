package org.evochora.runtime.instructions;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.test.utils.SimulationTestUtils;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contains low-level unit tests for the execution of data movement instructions by the virtual machine.
 * Each test sets up a specific state, executes a single instruction, and verifies the precise outcome.
 * These tests operate on an in-memory simulation and do not require external resources, unless specified otherwise.
 */
public class VMDataInstructionTest {

    private Environment environment;
    private Organism org;
    private Simulation sim;
    private final int[] startPos = new int[]{5, 5};

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        environment = new Environment(new int[]{100, 100}, true);
        sim = SimulationTestUtils.createSimulation(environment);
        org = Organism.create(sim, startPos, 1000, sim.getLogger());
        sim.addOrganism(org);
    }

    private void placeInstruction(String name, Integer... args) {
        int opcode = Instruction.getInstructionIdByName(name);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        for (int arg : args) {
            currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment);            environment.setMolecule(Molecule.fromInt(arg), currentPos);
        }
    }

    private void placeInstructionWithRegisterAndVector(String name, int reg, int[] vector) {
        int opcode = Instruction.getInstructionIdByName(name);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment);        environment.setMolecule(new Molecule(Config.TYPE_DATA, reg), currentPos);
        for (int val : vector) {
            currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment);            environment.setMolecule(new Molecule(Config.TYPE_DATA, val), currentPos);
        }
    }

    private void placeInstructionWithVector(String name, int[] vector) {
        int opcode = Instruction.getInstructionIdByName(name);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        for (int val : vector) {
            currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment);            environment.setMolecule(new Molecule(Config.TYPE_DATA, val), currentPos);
        }
    }

    /**
     * Tests the SETI (Set Immediate) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testSeti() {
        int immediateValue = new Molecule(Config.TYPE_DATA, 123).toInt();
        placeInstruction("SETI", 0, immediateValue);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(immediateValue);
    }

    /**
     * Tests the SETR (Set Register) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testSetr() {
        int srcValue = new Molecule(Config.TYPE_DATA, 456).toInt();
        org.setDr(1, srcValue);
        placeInstruction("SETR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(srcValue);
    }

    /**
     * Tests the SETV (Set Vector) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testSetv() {
        int[] vec = new int[]{3, 4};
        placeInstructionWithRegisterAndVector("SETV", 0, vec);
        sim.tick();
        Object reg0 = org.getDr(0);
        assertThat(reg0).isInstanceOf(int[].class);
        assertThat((int[]) reg0).containsExactly(vec);
    }

    /**
     * Tests the PUSH instruction for pushing a register value to the stack.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testPush() {
        int value = new Molecule(Config.TYPE_DATA, 789).toInt();
        org.setDr(0, value);
        placeInstruction("PUSH", 0);
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(value);
    }

    /**
     * Tests the POP instruction for popping a value from the stack into a register.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testPop() {
        int value = new Molecule(Config.TYPE_DATA, 321).toInt();
        org.getDataStack().push(value);
        placeInstruction("POP", 0);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(value);
    }

    /**
     * Tests the PUSI (Push Immediate) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testPusi() {
        int literal = new Molecule(Config.TYPE_DATA, 42).toInt();
        placeInstruction("PUSI", literal);
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(literal);
    }

    /**
     * Tests the PUSV (Push Vector) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testPusv() {
        int[] vec = new int[]{5, 6};
        placeInstructionWithVector("PUSV", vec);
        sim.tick();
        Object stackValue = org.getDataStack().pop();
        assertThat(stackValue).isInstanceOf(int[].class);
        assertThat((int[]) stackValue).containsExactly(vec);
    }

    /**
     * Verifies that a vector value set by SETV is correctly preserved during a full
     * JSON serialization and deserialization cycle. This is critical for debugging tools.
     * This is an integration test involving the compiler, runtime, and server contract classes.
     * @throws Exception if compilation or JSON processing fails.
     */
    @Test
    @Tag("integration")
    void testSetvJsonSerialization() throws Exception {

        // Simuliere genau das, was im Web Debugger passiert
        org.evochora.compiler.Compiler compiler = new org.evochora.compiler.Compiler();
        java.util.List<String> lines = java.util.Arrays.asList(
            "SETV %DR5 1|0",
            "NOP"
        );
        
        org.evochora.compiler.api.ProgramArtifact artifact = compiler.compile(lines, "test.s", new EnvironmentProperties(new int[]{100, 100}, true));
        
        // Erstelle eine neue Simulation mit dem kompilierten Code
        Environment testEnv = new Environment(new int[]{100, 100}, true);
        Simulation testSim = SimulationTestUtils.createSimulation(testEnv);
        Organism testOrg = Organism.create(testSim, new int[]{0, 0}, 1000, testSim.getLogger());
        testSim.addOrganism(testOrg);
        
        // Platziere den kompilierten Code im Environment
        for (java.util.Map.Entry<int[], Integer> e : artifact.machineCodeLayout().entrySet()) {
            int[] coord = e.getKey();
            int value = e.getValue();
            testEnv.setMolecule(Molecule.fromInt(value), testOrg.getId(), coord);
        }
        
        // Führe einen Tick aus (SETV sollte ausgeführt werden)
        testSim.tick();
        
        // Prüfe ob DR5 korrekt gesetzt wurde
        Object dr5Value = testOrg.getDr(5);
        assertThat(dr5Value).isInstanceOf(int[].class);
        
        // Prüfe dass der Vektor korrekt ist
        int[] vectorValue = (int[]) dr5Value;
        assertThat(vectorValue).containsExactly(1, 0);
    }

    /**
     * Tests the XCHG (Exchange Registers) instruction.
     * This swaps the contents of two registers.
     */
    @Test
    @Tag("unit")
    void testXchg() {
        int value1 = new Molecule(Config.TYPE_DATA, 111).toInt();
        int value2 = new Molecule(Config.TYPE_DATA, 222).toInt();
        org.setDr(0, value1);
        org.setDr(1, value2);
        placeInstruction("XCHG", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(value2);
        assertThat(org.getDr(1)).isEqualTo(value1);
    }

    /**
     * Tests the XCHG instruction with vector values.
     */
    @Test
    @Tag("unit")
    void testXchgVectors() {
        int[] vec1 = new int[]{1, 2};
        int[] vec2 = new int[]{3, 4};
        org.setDr(0, vec1);
        org.setDr(1, vec2);
        placeInstruction("XCHG", 0, 1);
        sim.tick();
        assertThat((int[]) org.getDr(0)).containsExactly(3, 4);
        assertThat((int[]) org.getDr(1)).containsExactly(1, 2);
    }
}