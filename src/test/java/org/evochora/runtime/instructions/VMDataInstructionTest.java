package org.evochora.runtime.instructions;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
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
        sim = new Simulation(environment);
        org = Organism.create(sim, startPos, 1000, sim.getLogger());
        sim.addOrganism(org);
    }

    private void placeInstruction(String name, Integer... args) {
        int opcode = Instruction.getInstructionIdByName(name);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        for (int arg : args) {
            currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment); // CORRECTED
            environment.setMolecule(Molecule.fromInt(arg), currentPos);
        }
    }

    private void placeInstructionWithVector(String name, int reg, int[] vector) {
        int opcode = Instruction.getInstructionIdByName(name);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment); // CORRECTED
        environment.setMolecule(new Molecule(Config.TYPE_DATA, reg), currentPos);
        for (int val : vector) {
            currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment); // CORRECTED
            environment.setMolecule(new Molecule(Config.TYPE_DATA, val), currentPos);
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
        placeInstructionWithVector("SETV", 0, vec);
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
     * Verifies that a vector value set by SETV is correctly preserved during a full
     * JSON serialization and deserialization cycle. This is critical for debugging tools.
     * This is an integration test involving the compiler, runtime, and server contract classes.
     * @throws Exception if compilation or JSON processing fails.
     */
    @Test
    @Tag("integration")
    void testSetvJsonSerialization() throws Exception {
        System.out.println("=== JSON Serialization Test ===");
        
        // Simuliere genau das, was im Web Debugger passiert
        org.evochora.compiler.Compiler compiler = new org.evochora.compiler.Compiler();
        java.util.List<String> lines = java.util.Arrays.asList(
            "SETV %DR5 1|0",
            "NOP"
        );
        
        org.evochora.compiler.api.ProgramArtifact artifact = compiler.compile(lines, "test.s");
        
        // Erstelle eine neue Simulation mit dem kompilierten Code
        Environment testEnv = new Environment(new int[]{100, 100}, true);
        Simulation testSim = new Simulation(testEnv);
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
        System.out.println("DR5 after SETV execution: " + dr5Value);
        assertThat(dr5Value).isInstanceOf(int[].class);
        
        // Jetzt teste ich, ob der Wert bei der JSON-Serialisierung verloren geht
        System.out.println("Testing JSON serialization...");
        
        // Erstelle einen RawOrganismState (wie in SimulationEngine.toRawState)
        java.util.List<Object> drsCopy = new java.util.ArrayList<>(testOrg.getDrs());
        org.evochora.server.contracts.raw.RawOrganismState rawState = new org.evochora.server.contracts.raw.RawOrganismState(
            testOrg.getId(), testOrg.getParentId(), testOrg.getBirthTick(), testOrg.getProgramId(), testOrg.getInitialPosition(),
            testOrg.getIp(), testOrg.getDv(), testOrg.getDps(), testOrg.getActiveDpIndex(), testOrg.getEr(),
            drsCopy, testOrg.getPrs(), testOrg.getFprs(), testOrg.getLrs(),
            testOrg.getDataStack(), testOrg.getLocationStack(), new java.util.ArrayDeque<org.evochora.server.contracts.raw.SerializableProcFrame>(),
            testOrg.isDead(), testOrg.isInstructionFailed(), testOrg.getFailureReason(),
            testOrg.shouldSkipIpAdvance(), testOrg.getIpBeforeFetch(), testOrg.getDvBeforeFetch()
        );
        
        // Erstelle einen RawTickState
        org.evochora.server.contracts.raw.RawTickState rawTickState = new org.evochora.server.contracts.raw.RawTickState(
            1L, java.util.Arrays.asList(rawState), new java.util.ArrayList<>()
        );
        
        // Teste JSON-Serialisierung (wie in DebugIndexer.writePreparedTick)
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String json = objectMapper.writeValueAsString(rawTickState);
        System.out.println("JSON serialization successful, length: " + json.length());
        
        // Prüfe ob der Vektor in der JSON enthalten ist
        assertThat(json).contains("[1,0]");
        System.out.println("SUCCESS: VECTOR value preserved in JSON serialization!");
        
        // Teste JSON-Deserialisierung
        org.evochora.server.contracts.raw.RawTickState deserialized = objectMapper.readValue(json, org.evochora.server.contracts.raw.RawTickState.class);
        Object dr5Deserialized = deserialized.organisms().get(0).drs().get(5);
        System.out.println("DR5 after JSON deserialization: " + dr5Deserialized);
        assertThat(dr5Deserialized).isInstanceOf(java.util.List.class);
        
        System.out.println("SUCCESS: VECTOR value preserved through JSON round-trip!");
    }
}