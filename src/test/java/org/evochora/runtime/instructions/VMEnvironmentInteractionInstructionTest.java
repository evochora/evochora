package org.evochora.runtime.instructions;

import static org.assertj.core.api.Assertions.assertThat;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.test.utils.SimulationTestUtils;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Contains low-level unit tests for the execution of environment interaction instructions (PEEK, POKE)
 * by the virtual machine. Each test sets up a specific state, executes a single instruction,
 * and verifies the precise outcome on the organism's state and the environment.
 * These tests operate on an in-memory simulation and do not require external resources.
 */
public class VMEnvironmentInteractionInstructionTest {

    private Environment environment;
    private Organism org;
    private Simulation sim;
    private final int[] startPos = new int[]{5, 5};

    /**
     * The marker register the write tests run with. It is non-zero, so a written DATA molecule
     * keeps its type: with marker register 0 the environment would store it as STATE.
     */
    private static final int WRITE_MARKER = 1;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        environment = new Environment(new int[]{96, 96}, true);
        sim = SimulationTestUtils.createSimulation(environment);
        org = Organism.create(sim, startPos, 2000);
        sim.addOrganism(org);
    }

    private void placeInstruction(String name) {
        int opcode = Instruction.getInstructionIdByName(name);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org.getIp());
    }

    /**
     * The form a molecule written under {@link #WRITE_MARKER} takes in the cell: the type and value
     * are kept and the marker register is stamped into it.
     */
    private static int storedUnderWriteMarker(int moleculeInt) {
        Molecule molecule = Molecule.fromInt(moleculeInt);
        return new Molecule(molecule.type(), molecule.value(), WRITE_MARKER).toInt();
    }

    private void placeInstruction(String name, Integer... args) {
        placeInstruction(name);
        int[] currentPos = org.getIp();
        for (int arg : args) {
            currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment);            environment.setMolecule(new Molecule(Config.TYPE_DATA, arg), currentPos);
        }
    }

    private void placeInstructionWithVector(String name, int reg, int[] vector) {
        int opcode = Instruction.getInstructionIdByName(name);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment);        environment.setMolecule(new Molecule(Config.TYPE_DATA, reg), currentPos);
        for (int val : vector) {
            currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment);            environment.setMolecule(new Molecule(Config.TYPE_DATA, val), currentPos);
        }
    }

    /**
     * Tests the POKE instruction (write from register to location specified by register vector).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testPoke() {
        int[] vec = new int[]{0, 1};
        int payload = new Molecule(Config.TYPE_DATA, 77).toInt();
        org.setMr(WRITE_MARKER);
        org.writeOperand(0, payload);
        org.writeOperand(1, vec);

        placeInstruction("POKE", 0, 1);
        int[] targetPos = org.getTargetCoordinate(org.getDp(0), vec, environment);
        sim.tick();

        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
        assertThat(environment.getMolecule(targetPos).toInt()).isEqualTo(storedUnderWriteMarker(payload));
        // POKE(DATA) costs base 1 + 5
        assertThat(org.getEr()).isLessThanOrEqualTo(2000 - 1 - 5);
    }

    /**
     * Tests the POKI instruction (write from register to location specified by immediate vector).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testPoki() {
        int payload = new Molecule(Config.TYPE_DATA, 88).toInt();
        org.setMr(WRITE_MARKER);
        org.writeOperand(0, payload);

        placeInstruction("POKI", 0, 0, 1);
        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment);
        sim.tick();

        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
        assertThat(environment.getMolecule(target).toInt()).isEqualTo(storedUnderWriteMarker(payload));
        // POKI(DATA) costs base 1 + 5
        assertThat(org.getEr()).isLessThanOrEqualTo(2000 - 1 - 5);
    }

    /**
     * A write may only reach a cell adjacent to the data pointer. A vector that names no neighbour
     * is mapped to the nearest one, and the write lands there; the cell the unmapped vector pointed
     * at stays untouched, because only a claimed neighbour takes part in conflict resolution.
     */
    @Test
    @Tag("unit")
    void pokiWithNonUnitVectorWritesToTheNearestNeighbour() {
        int payload = new Molecule(Config.TYPE_DATA, 88).toInt();
        org.setMr(WRITE_MARKER);
        org.writeOperand(0, payload);
        // Away from the row the instruction itself occupies, so the neighbour it writes to is empty.
        org.setDp(0, new int[]{startPos[0], startPos[1] + 1});

        int[] diagonal = org.getTargetCoordinate(org.getDp(0), new int[]{1, 1}, environment);
        int[] neighbour = org.getTargetCoordinate(org.getDp(0), new int[]{1, 0}, environment);

        placeInstruction("POKI", 0, 1, 1);
        sim.tick();

        assertThat(org.isInstructionFailed()).as(org.getFailureReason()).isFalse();
        assertThat(environment.getMolecule(neighbour).toInt())
                .as("the write lands on the nearest neighbour")
                .isEqualTo(storedUnderWriteMarker(payload));
        assertThat(environment.getMolecule(diagonal).isEmpty())
                .as("the cell two steps away is never addressed")
                .isTrue();
    }

    /**
     * The reading counterpart: the molecule comes from the nearest neighbour, and the cell the
     * unmapped vector pointed at keeps what it holds.
     */
    @Test
    @Tag("unit")
    void pekiWithNonUnitVectorReadsTheNearestNeighbour() {
        org.setDp(0, new int[]{startPos[0], startPos[1] + 1});
        int[] diagonal = org.getTargetCoordinate(org.getDp(0), new int[]{1, 1}, environment);
        int[] neighbour = org.getTargetCoordinate(org.getDp(0), new int[]{1, 0}, environment);
        int inDiagonal = new Molecule(Config.TYPE_DATA, 11).toInt();
        int inNeighbour = new Molecule(Config.TYPE_DATA, 22).toInt();
        environment.setMolecule(Molecule.fromInt(inDiagonal), diagonal);
        environment.setMolecule(Molecule.fromInt(inNeighbour), neighbour);

        placeInstructionWithVector("PEKI", 0, new int[]{1, 1});
        sim.tick();

        assertThat(org.isInstructionFailed()).as(org.getFailureReason()).isFalse();
        assertThat((int) org.readOperand(0))
                .as("the molecule comes from the nearest neighbour")
                .isEqualTo(inNeighbour);
        assertThat(environment.getMolecule(diagonal).toInt())
                .as("the cell two steps away keeps its molecule")
                .isEqualTo(inDiagonal);
    }

    /**
     * A write without a displacement lands on the cell the data pointer stands on. The vector of a
     * world interaction is an offset, and an offset of none is a cell like any other it may reach.
     */
    @Test
    @Tag("unit")
    void pokiWithoutADisplacementWritesUnderTheDataPointer() {
        int payload = new Molecule(Config.TYPE_DATA, 88).toInt();
        org.setMr(WRITE_MARKER);
        org.writeOperand(0, payload);
        org.setDp(0, new int[]{startPos[0], startPos[1] + 1});
        int[] underPointer = org.getDp(0).clone();

        placeInstruction("POKI", 0, 0, 0);
        sim.tick();

        assertThat(org.isInstructionFailed()).as(org.getFailureReason()).isFalse();
        assertThat(environment.getMolecule(underPointer).toInt())
                .isEqualTo(storedUnderWriteMarker(payload));
    }

    /**
     * An instruction whose operands already failed never reaches the environment. Conflict
     * resolution skips an instruction that claims no cell, so a write getting through would be one
     * that no arbitration ever saw.
     */
    @Test
    @Tag("unit")
    void pokeWithAnUnreadableRegisterWritesNothing() {
        org.setMr(WRITE_MARKER);
        org.writeOperand(1, new int[]{0, 1});
        int[] target = org.getTargetCoordinate(org.getDp(0), new int[]{0, 1}, environment);

        // 4096 lies outside the register table, so resolving the first operand fails the instruction.
        placeInstruction("POKE", 4096, 1);
        sim.tick();

        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(environment.getMolecule(target).isEmpty())
                .as("nothing is written on behalf of an instruction that already failed")
                .isTrue();
    }

    /**
     * Tests the PEEK instruction (read to register from location specified by register vector).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testPeek() {
        org.setDp(0, org.getIp());        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment);        int payload = new Molecule(Config.TYPE_DATA, 7).toInt();
        environment.setMolecule(Molecule.fromInt(payload), target);

        org.writeOperand(1, vec);
        placeInstruction("PEEK", 0, 1);
        sim.tick();

        assertThat((int) org.readOperand(0)).isEqualTo(payload);
        assertThat(environment.getMolecule(target).isEmpty()).isTrue();
    }

    /**
     * Tests the PEKI instruction (read to register from location specified by immediate vector).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testPeki() {
        org.setDp(0, org.getIp());        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment);        int payload = new Molecule(Config.TYPE_DATA, 11).toInt();
        environment.setMolecule(Molecule.fromInt(payload), target);

        placeInstructionWithVector("PEKI", 0, vec);
        sim.tick();

        assertThat((int) org.readOperand(0)).isEqualTo(payload);
        assertThat(environment.getMolecule(target).isEmpty()).isTrue();
    }

    /**
     * Tests the PEKS instruction (read to stack from location specified by stack vector).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testPeks() {
        org.setDp(0, org.getIp());        int[] vec = new int[]{-1, 0};
        int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment);        int payload = new Molecule(Config.TYPE_DATA, 9).toInt();
        environment.setMolecule(Molecule.fromInt(payload), target);

        org.getDataStack().push(vec);
        placeInstruction("PEKS");
        sim.tick();

        assertThat(org.getDataStack().pop()).isEqualTo(payload);
        assertThat(environment.getMolecule(target).isEmpty()).isTrue();
    }

    /**
     * Tests the PPKR instruction (PEEK+POKE with register operands).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testPpkr() {
        int[] vec = new int[]{0, 1};
        int originalPayload = new Molecule(Config.TYPE_DATA, 99).toInt();
        int newPayload = new Molecule(Config.TYPE_DATA, 111).toInt();
        
        // Set up target cell with original content (owned by foreign organism to test foreign peek costs)
        int[] targetPos = org.getTargetCoordinate(org.getDp(0), vec, environment);
        environment.setMolecule(Molecule.fromInt(originalPayload), 999, targetPos); // Foreign owner ID 999
        
        // Set up registers: target reg, vector reg
        org.setMr(WRITE_MARKER);
        org.writeOperand(0, newPayload); // register contains value to write, will receive peeked value
        org.writeOperand(1, vec); // vector register

        placeInstruction("PPKR", 0, 1);
        sim.tick();

        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();

        // Verify PPKR worked correctly: register contains peeked value, cell contains new value
        assertThat((int) org.readOperand(0)).isEqualTo(originalPayload).as("Register should contain DATA:99 (read from cell 0|1)");
        assertThat(environment.getMolecule(targetPos).toInt()).isEqualTo(storedUnderWriteMarker(newPayload)).as("Cell should contain DATA:111 (written from %DR0)");
        
        // Verify energy costs: PPKR(DATA->DATA) costs peek 5 (foreign DATA) + poke 6 (DATA) = 11
        int expectedEnergy = 2000 - 5 - 6;
        assertThat(org.getEr()).isEqualTo(expectedEnergy).as("Energy should be consumed correctly: peek 5 + poke 6 = 11");
    }

    /**
     * Tests the PPKI instruction (PEEK+POKE with immediate vector).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testPpki() {
        int[] vec = new int[]{0, 1}; // PPKI reads [0, 1] from immediate operands
        int originalPayload = new Molecule(Config.TYPE_DATA, 99).toInt();
        int newPayload = new Molecule(Config.TYPE_DATA, 111).toInt();
        
        // Set up target cell with original content at the position where PPKI will actually look
        // PPKI will look at [5, 6] (DP + [0, 1])
        int[] targetPos = org.getTargetCoordinate(org.getDp(0), vec, environment);
        environment.setMolecule(Molecule.fromInt(originalPayload), 999, targetPos); // Foreign owner ID 999
        
        
        // Set up register: contains value to write, will receive peeked value
        org.setMr(WRITE_MARKER);
        org.writeOperand(0, newPayload); // register contains value to write

        int initialEr = org.getEr();
        placeInstruction("PPKI", 0, 0, 1); // PPKI %REG <Vector> - Vector components: [0,1]
        sim.tick();

        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();

        // Verify PPKI worked correctly: register contains peeked value, cell contains new value
        assertThat((int) org.readOperand(0)).isEqualTo(originalPayload).as("Register should contain DATA:99 (read from cell 0|1)");
        assertThat(environment.getMolecule(targetPos).toInt()).isEqualTo(storedUnderWriteMarker(newPayload)).as("Cell should contain DATA:111 (written from %DR0)");
        
        // Verify energy costs: PPKI(DATA->DATA) costs peek 5 (foreign DATA) + poke 6 (DATA) = 11
        int expectedEnergy = initialEr - 5 - 6;
        assertThat(org.getEr()).isEqualTo(expectedEnergy).as("Energy should be consumed correctly: peek 5 + poke 6 = 11");
    }

    /**
     * Tests the PPKS instruction (PEEK+POKE with stack operands).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testPpks() {
        int[] vec = new int[]{0, 1};
        int originalPayload = new Molecule(Config.TYPE_DATA, 99).toInt();
        int newPayload = new Molecule(Config.TYPE_DATA, 111).toInt();
        
        // Set up target cell with original content (owned by foreign organism to test foreign peek costs)
        int[] targetPos = org.getTargetCoordinate(org.getDp(0), vec, environment);
        environment.setMolecule(Molecule.fromInt(originalPayload), 999, targetPos); // Foreign owner ID 999
        
        // Set up stack: new value and vector (PPKS reads vector from stack)
        org.setMr(WRITE_MARKER);
        org.getDataStack().push(vec);
        org.getDataStack().push(newPayload);

        placeInstruction("PPKS");
        sim.tick();

        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
        
        // Verify PPKS worked correctly: stack contains peeked value, cell contains new value
        assertThat(org.getDataStack().pop()).isEqualTo(originalPayload).as("Stack should contain DATA:99 (read from cell 0|1)");
        assertThat(environment.getMolecule(targetPos).toInt()).isEqualTo(storedUnderWriteMarker(newPayload)).as("Cell should contain DATA:111 (written from stack)");
        
        // Verify energy costs: PPKS(DATA->DATA) costs peek 5 (foreign DATA) + poke 6 (DATA) = 11
        int expectedEnergy = 2000 - 5 - 6;
        assertThat(org.getEr()).isEqualTo(expectedEnergy).as("Energy should be consumed correctly: peek 5 + poke 6 = 11");
    }

    /**
     * Tests that PPKR works when target cell is empty (stores empty molecule and writes new value).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testPpkrWorksOnEmptyCell() {
        int[] vec = new int[]{0, 1};
        int newPayload = new Molecule(Config.TYPE_DATA, 88).toInt();
        int emptyMolecule = new Molecule(Config.TYPE_CODE, 0).toInt();
        
        // Target cell is empty (not set)
        int[] targetPos = org.getTargetCoordinate(org.getDp(0), vec, environment);
        
        // Set up registers
        org.setMr(WRITE_MARKER);
        org.writeOperand(0, newPayload); // register contains value to write, will receive peeked value
        org.writeOperand(1, vec); // vector register

        placeInstruction("PPKR", 0, 1);
        sim.tick();

        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();

        // Verify PPKR on empty cell: register contains empty molecule, cell contains new value
        assertThat((int) org.readOperand(0)).isEqualTo(emptyMolecule).as("Register should contain empty molecule (CODE:0) when cell was empty");
        assertThat(environment.getMolecule(targetPos).toInt()).isEqualTo(storedUnderWriteMarker(newPayload)).as("Cell should contain the new molecule that was written");
        
        // Verify energy costs: PPKR on empty cell: peek 0 (unowned empty) + poke 6 (DATA) = 6
        int expectedEnergy = 2000 - 0 - 6;
        assertThat(org.getEr()).isEqualTo(expectedEnergy).as("Energy should be consumed correctly: peek 0 + poke 6 = 6 (no peek costs on empty cell)");
    }


    /**
     * Places an instruction and its scalar arguments for an organism other than the one the test
     * fixture creates.
     */
    private void placeInstructionFor(Organism organism, String name, Integer... args) {
        int opcode = Instruction.getInstructionIdByName(name);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcode), organism.getIp());
        int[] currentPos = organism.getIp();
        for (int arg : args) {
            currentPos = organism.getNextInstructionPosition(currentPos, organism.getDv(), environment);
            environment.setMolecule(new Molecule(Config.TYPE_DATA, arg), currentPos);
        }
    }

    /**
     * A DATA molecule written while the marker register is 0 is stored as STATE: what an organism
     * writes in the ephemeral marker class is its own memory, not part of a genome.
     */
    @Test
    @Tag("unit")
    void testPokeStoresDataWrittenWithoutMarkerAsState() {
        int[] vec = new int[]{0, 1};
        int payload = new Molecule(Config.TYPE_DATA, 55).toInt();
        org.writeOperand(0, payload);
        org.writeOperand(1, vec);

        placeInstruction("POKE", 0, 1);
        int[] targetPos = org.getTargetCoordinate(org.getDp(0), vec, environment);
        sim.tick();

        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
        Molecule stored = environment.getMolecule(targetPos);
        assertThat(stored.type()).isEqualTo(Config.TYPE_STATE);
        assertThat(stored.value()).isEqualTo(55);
        assertThat(stored.marker()).isEqualTo(0);
    }

    /**
     * The POKE path and the PPK swap path store the same form for the same written value: both
     * resolve the stored molecule through the same rule.
     */
    @Test
    @Tag("unit")
    void testPokeAndPpkStoreTheSameFormForTheSameInput() {
        int[] vec = new int[]{0, 1};
        int payload = new Molecule(Config.TYPE_DATA, 64).toInt();

        org.writeOperand(0, payload);
        org.writeOperand(1, vec);
        placeInstruction("POKE", 0, 1);
        int[] pokeTarget = org.getTargetCoordinate(org.getDp(0), vec, environment);

        Organism other = Organism.create(sim, new int[]{20, 20}, 2000);
        sim.addOrganism(other);
        other.writeOperand(0, payload);
        int[] ppkTarget = other.getTargetCoordinate(other.getDp(0), vec, environment);
        placeInstructionFor(other, "PPKI", 0, 0, 1);

        sim.tick();

        assertThat(org.isInstructionFailed()).as("POKE failed: " + org.getFailureReason()).isFalse();
        assertThat(other.isInstructionFailed()).as("PPKI failed: " + other.getFailureReason()).isFalse();
        assertThat(environment.getMolecule(ppkTarget).toInt())
                .as("both write paths store the same form")
                .isEqualTo(environment.getMolecule(pokeTarget).toInt());
        assertThat(environment.getMolecule(pokeTarget).type()).isEqualTo(Config.TYPE_STATE);
    }

    /**
     * Tests that POKE correctly uses the organism's MR (Molecule Marker Register)
     * when writing a molecule to the environment.
     * This verifies that the marker is propagated from the organism to the written molecule.
     */
    @Test
    @Tag("unit")
    void testPokeUsesMarkerFromMrRegister() {
        int[] vec = new int[]{0, 1};
        int payload = new Molecule(Config.TYPE_DATA, 42).toInt();
        
        // Set the organism's MR to a specific value
        int expectedMarker = 7;
        org.setMr(expectedMarker);
        
        org.writeOperand(0, payload);
        org.writeOperand(1, vec);

        placeInstruction("POKE", 0, 1);
        int[] targetPos = org.getTargetCoordinate(org.getDp(0), vec, environment);

        sim.tick();

        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
        
        // Verify the molecule was written with the correct marker
        Molecule writtenMolecule = environment.getMolecule(targetPos);
        assertThat(writtenMolecule.value()).isEqualTo(42).as("Molecule value should be 42");
        assertThat(writtenMolecule.type()).isEqualTo(Config.TYPE_DATA).as("Molecule type should be DATA");
        
        // Molecule.marker() now returns the marker value directly (0-15 range)
        assertThat(writtenMolecule.marker()).isEqualTo(expectedMarker)
            .as("Molecule marker should be set from organism's MR register");
    }

    /**
     * Tests that MR is masked to 4 bits (0-15) when set.
     */
    @Test
    @Tag("unit")
    void testMrIsMaskedTo4Bits() {
        // Set MR to a value larger than 4 bits
        org.setMr(0xFF); // 255 in decimal
        
        // Verify it was masked to 4 bits
        assertThat(org.getMr()).isEqualTo(0xF).as("MR should be masked to 4 bits (0-15)");
    }

    // ==================== Entropy Tests ====================

    @Test
    @Tag("unit")
    void testEntropyIncreasesWithInstructionExecution() {
        // Given: Organism starts with SR=0
        assertThat(org.getSr()).isEqualTo(0);
        
        // Place a simple NOP instruction
        placeInstruction("NOP");
        
        // When: Execute one tick
        sim.tick();
        
        // Then: Entropy should have increased (instruction cost = 1)
        assertThat(org.getSr()).isGreaterThan(0);
    }

    @Test
    @Tag("unit")
    void testPokeReducesEntropy() {
        // Given: Organism with some entropy
        org.addSr(100);
        int initialSr = org.getSr();
        assertThat(initialSr).isEqualTo(100);
        
        // Setup POKE to write DATA:50 to an empty cell
        int moleculeValue = 50;
        int[] vec = new int[]{0, 1};
        org.setMr(WRITE_MARKER);
        org.writeOperand(0, new Molecule(Config.TYPE_DATA, moleculeValue).toInt());
        org.writeOperand(1, vec);
        placeInstruction("POKE", 0, 1);
        
        // When: Execute one tick
        sim.tick();
        
        // Then: Entropy should have decreased by the molecule value
        // POKE with DATA:50 costs: 6 energy (from test config)
        // POKE does NOT generate entropy from energy cost (only dissipates entropy)
        // Entropy dissipated: 50 (molecule value, entropy-permille = -1000 means 100% dissipation)
        // Net effect: 0 - 50 = -50
        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
        assertThat(org.getSr()).isLessThan(initialSr);
        // Entropy should be: 100 + 0 (no generation) - 50 (dissipation) = 50
        assertThat(org.getSr()).isEqualTo(50);
    }

    @org.junit.jupiter.api.AfterEach
    void assertNoInstructionFailure() {
        // Only assert no failure for tests that don't expect failures
        // Tests that expect failures (like testPpkrFailsOnEmptyCell) will handle their own assertions
    }

    /**
     * Verifies that PEKS fails with a data stack overflow when its vector operand leaves the stack
     * at the depth limit, and that the target cell keeps its molecule because the read is never
     * committed.
     */
    @Test
    @Tag("unit")
    void testPeksDataStackOverflow() {
        org.setDp(0, org.getIp());
        int[] vec = new int[]{-1, 0};
        int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment);
        int payload = new Molecule(Config.TYPE_DATA, 9).toInt();
        environment.setMolecule(Molecule.fromInt(payload), target);

        int filler = new Molecule(Config.TYPE_DATA, 1).toInt();
        for (int i = 0; i < Config.DS_MAX_DEPTH; i++) {
            org.getDataStack().push(filler);
        }
        org.getDataStack().push(vec);

        placeInstruction("PEKS");
        sim.tick();

        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).contains("Data stack overflow");
        assertThat(org.getDataStack()).hasSize(Config.DS_MAX_DEPTH);
        assertThat(environment.getMolecule(target).toInt()).isEqualTo(payload);
    }
}
