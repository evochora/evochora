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
 * Contains low-level unit tests for the execution of various state-related instructions
 * by the virtual machine. Each test verifies that an instruction correctly reads or modifies
 * the organism's internal state (e.g., energy, position, direction).
 * These tests operate on an in-memory simulation and do not require external resources.
 */
public class VMStateInstructionTest {

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

    private void placeInstructionWithVectorOnly(String name, int[] vector) {
        int opcode = Instruction.getInstructionIdByName(name);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        for (int val : vector) {
            currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment);            environment.setMolecule(new Molecule(Config.TYPE_DATA, val), currentPos);
        }
    }

    /**
     * Tests the TURN instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testTurn() {
        int[] vec = new int[]{1, 0};
        org.setDr(0, vec);
        placeInstruction("TURN", 0);
        sim.tick();
        assertThat(org.getDv()).isEqualTo(vec);
    }

    /**
     * Tests the SYNC instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testSync() {
        int[] expected = org.getIp();
        placeInstruction("SYNC");
        sim.tick();
        assertThat(org.getDp(0)).isEqualTo(expected);    }

    /**
     * Tests the NRG instruction (get energy to register).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testNrg() {
        placeInstruction("NRG", 0);
        sim.tick();
        int er = org.getEr();
        int regVal = (Integer) org.getDr(0);
        assertThat(Molecule.fromInt(regVal).toScalarValue()).isEqualTo(er);
    }

    /**
     * Tests the NRGS instruction (get energy to stack).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testNrgs() {
        placeInstruction("NRGS");
        sim.tick();
        int val = (Integer) org.getDataStack().pop();
        assertThat(Molecule.fromInt(val).toScalarValue()).isEqualTo(org.getEr());
    }

    /**
     * Tests the NTR instruction (get entropy to register).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testNtr() {
        placeInstruction("NTR", 0);
        sim.tick();
        int sr = org.getSr();
        int regVal = (Integer) org.getDr(0);
        assertThat(Molecule.fromInt(regVal).toScalarValue()).isEqualTo(sr);
    }

    /**
     * Tests the NTRS instruction (get entropy to stack).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testNtrs() {
        placeInstruction("NTRS");
        sim.tick();
        int val = (Integer) org.getDataStack().pop();
        assertThat(Molecule.fromInt(val).toScalarValue()).isEqualTo(org.getSr());
    }

    /**
     * Tests the DIFF instruction (get difference vector between DP and IP).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testDiff() {
        org.setDp(0, org.getIp());        placeInstruction("DIFF", 0);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new int[]{0, 0});
    }

    /**
     * Tests the POS instruction (get current position).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testPos() {
        placeInstruction("POS", 0);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new int[]{0,0});
    }

    /**
     * Tests the RAND instruction (get random number into register).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testRand() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 10).toInt());
        placeInstruction("RAND", 0);
        sim.tick();
        int val = Molecule.fromInt((Integer) org.getDr(0)).toScalarValue();
        assertThat(val).isGreaterThanOrEqualTo(0).isLessThan(10);
    }

    /**
     * Tests the RNDS instruction (get random number to stack).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testRnds() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 5).toInt());
        placeInstruction("RNDS");
        sim.tick();
        int val = Molecule.fromInt((Integer) org.getDataStack().pop()).toScalarValue();
        assertThat(val).isGreaterThanOrEqualTo(0).isLessThan(5);
    }

    /**
     * Tests the TRNI instruction (turn to immediate vector).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testTrniSetsDirection() {
        int[] vec = new int[]{0, 1};
        placeInstructionWithVectorOnly("TRNI", vec);
        sim.tick();
        assertThat(org.getDv()).isEqualTo(vec);
    }

    /**
     * Tests the TRNS instruction (turn to stack vector).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testTrnsSetsDirectionFromStack() {
        int[] vec = new int[]{-1, 0};
        org.getDataStack().push(vec);
        placeInstruction("TRNS");
        sim.tick();
        assertThat(org.getDv()).isEqualTo(vec);
    }

    /**
     * Tests the POSS instruction (push current position to stack).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testPossPushesRelativeIp() {
        placeInstruction("POSS");
        sim.tick();
        Object top = org.getDataStack().pop();
        assertThat(top).isInstanceOf(int[].class);
        assertThat((int[]) top).isEqualTo(new int[]{0,0});
    }

    /**
     * Tests the DIFS instruction (push difference vector to stack).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testDifsPushesDeltaBetweenActiveDpAndIp() {
        // Ensure DP0 = IP then SEEK to move DP by +1 on Y
        placeInstruction("SYNC");
        sim.tick();
        int[] vec = new int[]{0, 1};
        org.setDr(0, vec);
        placeInstruction("SEEK", 0);
        sim.tick();
        placeInstruction("DIFS");
        sim.tick();
        Object top = org.getDataStack().pop();
        assertThat(top).isInstanceOf(int[].class);
        // IP advanced by length of SEEK (2 cells: opcode + 1 register argument)
        // so delta = DP(0,1) - IP(2,0) relative to start -> depends on instruction lengths
        // We only assert delta.y > 0 to avoid hard-coding exact IP shift
        int[] delta = (int[]) top;
        assertThat(delta[1]).isGreaterThan(0);
    }

    /**
     * Tests the ADPI instruction (set active data pointer from immediate).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testAdpiSetsActiveDpIndex() {
        // Set active DP to 1, then SYNC should set DP1 to IP
        int dpIndexLiteral = new Molecule(Config.TYPE_DATA, 1).toInt();
        placeInstruction("ADPI", dpIndexLiteral);
        sim.tick();
        int[] expected = org.getIp();
        placeInstruction("SYNC");
        sim.tick();
        assertThat(org.getDp(1)).isEqualTo(expected);
        // DP0 should remain unchanged from default (startPos)
        assertThat(org.getDp(0)).isEqualTo(startPos);
    }

    /**
     * Tests the ADPR instruction (set active data pointer from register).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testAdprSetsActiveDpIndexFromRegister() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 1).toInt());
        placeInstruction("ADPR", 0);
        sim.tick();
        int[] expected = org.getIp();
        placeInstruction("SYNC");
        sim.tick();
        assertThat(org.getDp(1)).isEqualTo(expected);
    }

    /**
     * Tests the ADPS instruction (set active data pointer from stack).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testAdpsSetsActiveDpIndexFromStack() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 1).toInt());
        placeInstruction("ADPS");
        sim.tick();
        int[] expected = org.getIp();
        placeInstruction("SYNC");
        sim.tick();
        assertThat(org.getDp(1)).isEqualTo(expected);
    }

    /**
     * Tests the SEEK instruction (move DP by vector in register).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testSeek() {
        org.setDp(0, org.getIp());        int[] vec = new int[]{0, 1};
        org.setDr(0, vec);
        int[] expected = org.getTargetCoordinate(org.getDp(0), vec, environment);        placeInstruction("SEEK", 0);
        sim.tick();
        assertThat(org.getDp(0)).isEqualTo(expected);    }

    /**
     * Tests the SEKI instruction (move DP by immediate vector).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testSeki() {
        org.setDp(0, org.getIp());        int[] vec = new int[]{0, 1};
        int[] expected = org.getTargetCoordinate(org.getDp(0), vec, environment);        placeInstructionWithVectorOnly("SEKI", vec);
        sim.tick();
        assertThat(org.getDp(0)).isEqualTo(expected);    }

    /**
     * Tests the SEKS instruction (move DP by stack vector).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testSeks() {
        org.setDp(0, org.getIp());        int[] vec = new int[]{-1, 0};
        int[] expected = org.getTargetCoordinate(org.getDp(0), vec, environment);        org.getDataStack().push(vec);
        placeInstruction("SEKS");
        sim.tick();
        assertThat(org.getDp(0)).isEqualTo(expected);    }

    /**
     * Tests the SCAN instruction (read cell content without consuming).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testScan() {
        org.setDp(0, org.getIp());        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment);        int payload = new Molecule(Config.TYPE_STRUCTURE, 3).toInt();
        environment.setMolecule(Molecule.fromInt(payload), target);

        org.setDr(1, vec);
        placeInstruction("SCAN", 0, 1);
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(payload);
        assertThat(environment.getMolecule(target).toInt()).isEqualTo(payload);
    }

    /**
     * Tests the SCNI instruction (scan with immediate vector).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testScni() {
        org.setDp(0, org.getIp());        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment);        int payload = new Molecule(Config.TYPE_CODE, 42).toInt();
        environment.setMolecule(Molecule.fromInt(payload), target);

        placeInstructionWithVector("SCNI", 0, vec);
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(payload);
        assertThat(environment.getMolecule(target).toInt()).isEqualTo(payload);
    }

    /**
     * Tests the SCNS instruction (scan with stack vector).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testScns() {
        org.setDp(0, org.getIp());        int[] vec = new int[]{-1, 0};
        int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment);        int payload = new Molecule(Config.TYPE_ENERGY, 5).toInt();
        environment.setMolecule(Molecule.fromInt(payload), target);

        org.getDataStack().push(vec);
        placeInstruction("SCNS");
        sim.tick();

        assertThat(org.getDataStack().pop()).isEqualTo(payload);
        assertThat(environment.getMolecule(target).toInt()).isEqualTo(payload);
    }

    /**
     * Tests the GDVR instruction (get DV to register).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testGdvr() {
        int[] expectedDv = org.getDv();
        placeInstruction("GDVR", 0);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(expectedDv);
    }

    /**
     * Tests the GDVS instruction (get DV to stack).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testGdvs() {
        int[] expectedDv = org.getDv();
        placeInstruction("GDVS");
        sim.tick();
        Object top = org.getDataStack().pop();
        assertThat(top).isInstanceOf(int[].class);
        assertThat((int[]) top).isEqualTo(expectedDv);
    }

    /**
     * Tests the FRKS instruction (fork from stack).
     * This test verifies the correct stack order: [delta, energy, childDv] (top to bottom).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testFrks() {
        // Set up: ensure we have enough energy and a clear target location
        org.addEr(1000); // Give organism more energy for the fork operation
        org.setDp(0, org.getIp());
        
        // Prepare stack with values in the order expected by assembly code:
        // Stack order (top to bottom): [childDv, energy, delta]
        // 1. Push delta vector (direction to place child) - goes to bottom
        int[] delta = new int[]{1, 0};
        org.getDataStack().push(delta);
        
        // 2. Push energy for child - goes to middle
        int energy = new Molecule(Config.TYPE_DATA, 100).toInt();
        org.getDataStack().push(energy);
        
        // 3. Push child direction vector (childDv) - goes to top
        int[] childDv = new int[]{0, 1};
        org.getDataStack().push(childDv);
        
        // Verify stack has 3 elements
        assertThat(org.getDataStack().size()).isEqualTo(3);
        
        // Execute FRKS
        placeInstruction("FRKS");
        sim.tick();
        
        // Verify instruction succeeded
        assertThat(org.isInstructionFailed()).isFalse();
        
        // Verify stack is empty
        assertThat(org.getDataStack().size()).isEqualTo(0);
        
        // Verify child was created (simulation should have 2 organisms now)
        assertThat(sim.getOrganisms().size()).isEqualTo(2);
        
        // Find the child organism
        Organism child = sim.getOrganisms().stream()
            .filter(o -> o.getId() != org.getId())
            .findFirst()
            .orElse(null);
        
        assertThat(child).isNotNull();
        assertThat(child.getDv()).isEqualTo(childDv);
        assertThat(child.getParentId()).isEqualTo(org.getId());
    }

    // ==================== SMR Instruction Tests ====================

    @Test
    @Tag("unit")
    void testSmr_SetsMarkerRegisterFromDataRegister() {
        int markerValue = 7;
        org.setDr(0, new Molecule(Config.TYPE_DATA, markerValue).toInt());
        
        placeInstruction("SMR", 0); // SMR %DR0
        sim.tick();
        
        assertThat(org.getMr()).isEqualTo(markerValue);
    }

    @Test
    @Tag("unit")
    void testSmri_SetsMarkerRegisterFromImmediate() {
        int markerValue = 12;
        
        placeInstruction("SMRI", new Molecule(Config.TYPE_DATA, markerValue).toInt());
        sim.tick();
        
        assertThat(org.getMr()).isEqualTo(markerValue);
    }

    @Test
    @Tag("unit")
    void testSmrs_SetsMarkerRegisterFromStack() {
        int markerValue = 9;
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, markerValue).toInt());
        
        placeInstruction("SMRS");
        sim.tick();
        
        assertThat(org.getMr()).isEqualTo(markerValue);
        assertThat(org.getDataStack().isEmpty()).isTrue();
    }

    @Test
    @Tag("unit")
    void testSmr_MasksValueTo4Bits() {
        // Value 20 = 0b10100, should be masked to 0b0100 = 4
        int largeValue = 20;
        org.setDr(0, new Molecule(Config.TYPE_DATA, largeValue).toInt());
        
        placeInstruction("SMR", 0);
        sim.tick();
        
        assertThat(org.getMr()).isEqualTo(4); // 20 & 0xF = 4
    }

    @Test
    @Tag("unit")
    void testSmr_FailsWithNonDataType() {
        // Set register to ENERGY type instead of DATA
        org.setDr(0, new Molecule(Config.TYPE_ENERGY, 5).toInt());
        
        placeInstruction("SMR", 0);
        sim.tick();
        
        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).contains("DATA");
        
        // Reset for afterEach check
        org.resetTickState();
    }

    // ==================== FORK with Ownership Transfer Tests ====================

    @Test
    @Tag("unit")
    void testFork_TransfersOwnershipBasedOnMarker() {
        // Setup: Parent organism places molecules with marker=3
        org.setMr(3);
        int[] moleculePos1 = new int[]{10, 10};
        int[] moleculePos2 = new int[]{11, 11};
        int[] moleculePos3 = new int[]{12, 12}; // Different marker
        
        // Place molecules owned by parent with marker 3
        Molecule mol1 = new Molecule(Config.TYPE_DATA, 100, 3);
        Molecule mol2 = new Molecule(Config.TYPE_STRUCTURE, 50, 3);
        Molecule mol3 = new Molecule(Config.TYPE_DATA, 200, 5); // Different marker
        
        environment.setMolecule(mol1, org.getId(), moleculePos1);
        environment.setMolecule(mol2, org.getId(), moleculePos2);
        environment.setMolecule(mol3, org.getId(), moleculePos3);
        
        // Prepare FORK: delta=1|0, energy=100, dv=1|0
        org.setDr(0, new int[]{1, 0}); // delta
        org.setDr(1, new Molecule(Config.TYPE_DATA, 100).toInt()); // energy
        org.setDr(2, new int[]{1, 0}); // childDv
        
        // Place empty cell for child IP
        int[] childIpPos = org.getTargetCoordinate(org.getActiveDp(), new int[]{1, 0}, environment);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 0), childIpPos);
        
        placeInstruction("FORK", 0, 1, 2);
        sim.tick();
        
        // Find child organism
        Organism child = sim.getOrganisms().stream()
            .filter(o -> o.getId() != org.getId())
            .findFirst()
            .orElse(null);
        assertThat(child).isNotNull();
        
        // Molecules with marker=3 should now be owned by child
        assertThat(environment.getOwnerId(moleculePos1)).isEqualTo(child.getId());
        assertThat(environment.getOwnerId(moleculePos2)).isEqualTo(child.getId());
        
        // Molecule with marker=5 should still be owned by parent
        assertThat(environment.getOwnerId(moleculePos3)).isEqualTo(org.getId());
        
        // Transferred molecules should have marker reset to 0
        assertThat(environment.getMolecule(moleculePos1).marker()).isEqualTo(0);
        assertThat(environment.getMolecule(moleculePos2).marker()).isEqualTo(0);
        
        // Non-transferred molecule keeps its marker
        assertThat(environment.getMolecule(moleculePos3).marker()).isEqualTo(5);
    }

    // ==================== GMR Instruction Tests ====================

    @Test
    @Tag("unit")
    void testGmr_GetsMarkerRegisterToRegister() {
        org.setMr(7);

        placeInstruction("GMR", 0); // GMR %DR0
        sim.tick();

        Molecule result = Molecule.fromInt((Integer) org.getDr(0));
        assertThat(result.type()).isEqualTo(Config.TYPE_DATA);
        assertThat(result.value()).isEqualTo(7);
    }

    @Test
    @Tag("unit")
    void testGmrs_GetsMarkerRegisterToStack() {
        org.setMr(11);

        placeInstruction("GMRS");
        sim.tick();

        assertThat(org.getDataStack().isEmpty()).isFalse();
        Molecule result = Molecule.fromInt((Integer) org.getDataStack().pop());
        assertThat(result.type()).isEqualTo(Config.TYPE_DATA);
        assertThat(result.value()).isEqualTo(11);
    }

    @Test
    @Tag("unit")
    void testGmr_ReturnsZeroWhenMrNotSet() {
        // MR defaults to 0
        assertThat(org.getMr()).isEqualTo(0);

        placeInstruction("GMR", 0);
        sim.tick();

        Molecule result = Molecule.fromInt((Integer) org.getDr(0));
        assertThat(result.value()).isEqualTo(0);
    }

    // ==================== CMR Instruction Tests ====================

    @Test
    @Tag("unit")
    void testCmri_OrphansMoleculesWithMatchingMarker() {
        // Setup: Place molecules with different markers
        int[] pos1 = new int[]{10, 10};
        int[] pos2 = new int[]{11, 11};
        int[] pos3 = new int[]{12, 12};

        environment.setMolecule(new Molecule(Config.TYPE_DATA, 42, 3), pos1); // marker=3
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 43, 3), pos2); // marker=3
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 44, 5), pos3); // marker=5

        // Set ownership
        environment.setOwnerId(org.getId(), pos1);
        environment.setOwnerId(org.getId(), pos2);
        environment.setOwnerId(org.getId(), pos3);

        // Execute CMRI DATA:3 to orphan all marker=3 molecules
        placeInstruction("CMRI", new Molecule(Config.TYPE_DATA, 3).toInt());
        sim.tick();

        // Molecules with marker=3 should now have marker=0 AND owner=0 (orphaned)
        assertThat(environment.getMolecule(pos1).marker()).isEqualTo(0);
        assertThat(environment.getMolecule(pos2).marker()).isEqualTo(0);
        assertThat(environment.getOwnerId(pos1)).isEqualTo(0); // orphaned
        assertThat(environment.getOwnerId(pos2)).isEqualTo(0); // orphaned

        // Molecule with marker=5 should be unchanged (both marker and ownership)
        assertThat(environment.getMolecule(pos3).marker()).isEqualTo(5);
        assertThat(environment.getOwnerId(pos3)).isEqualTo(org.getId());
    }

    @Test
    @Tag("unit")
    void testCmr_OrphansMoleculesFromRegisterValue() {
        int[] pos1 = new int[]{15, 15};
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 99, 7), pos1); // marker=7
        environment.setOwnerId(org.getId(), pos1);

        org.setDr(0, new Molecule(Config.TYPE_DATA, 7).toInt());

        placeInstruction("CMR", 0); // CMR %DR0
        sim.tick();

        assertThat(environment.getMolecule(pos1).marker()).isEqualTo(0);
        assertThat(environment.getOwnerId(pos1)).isEqualTo(0); // orphaned
    }

    @Test
    @Tag("unit")
    void testCmrs_OrphansMoleculesFromStackValue() {
        int[] pos1 = new int[]{20, 20};
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 77, 9), pos1); // marker=9
        environment.setOwnerId(org.getId(), pos1);

        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 9).toInt());

        placeInstruction("CMRS");
        sim.tick();

        assertThat(environment.getMolecule(pos1).marker()).isEqualTo(0);
        assertThat(environment.getOwnerId(pos1)).isEqualTo(0); // orphaned
        assertThat(org.getDataStack().isEmpty()).isTrue();
    }

    @Test
    @Tag("unit")
    void testCmri_OnlyOrphansOwnMolecules() {
        // Create another organism
        Organism other = Organism.create(sim, new int[]{50, 50}, 500, sim.getLogger());
        sim.addOrganism(other);

        int[] ownPos = new int[]{10, 10};
        int[] otherPos = new int[]{11, 11};

        environment.setMolecule(new Molecule(Config.TYPE_DATA, 42, 3), ownPos);
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 43, 3), otherPos);

        environment.setOwnerId(org.getId(), ownPos);
        environment.setOwnerId(other.getId(), otherPos);

        // Execute CMRI DATA:3
        placeInstruction("CMRI", new Molecule(Config.TYPE_DATA, 3).toInt());
        sim.tick();

        // Own molecule should be orphaned (marker=0, owner=0)
        assertThat(environment.getMolecule(ownPos).marker()).isEqualTo(0);
        assertThat(environment.getOwnerId(ownPos)).isEqualTo(0); // orphaned

        // Other organism's molecule should be completely unchanged
        assertThat(environment.getMolecule(otherPos).marker()).isEqualTo(3);
        assertThat(environment.getOwnerId(otherPos)).isEqualTo(other.getId());
    }

    @Test
    @Tag("unit")
    void testCmr_FailsWithNonDataType() {
        // Use register variant to test type check (similar to testSmr_FailsWithNonDataType)
        org.setDr(0, new Molecule(Config.TYPE_ENERGY, 3).toInt());

        placeInstruction("CMR", 0);
        sim.tick();

        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).contains("DATA");

        org.resetTickState();
    }

    @org.junit.jupiter.api.AfterEach
    void assertNoInstructionFailure() {
        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
    }
}