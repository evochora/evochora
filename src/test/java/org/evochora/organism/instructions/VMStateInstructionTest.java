package org.evochora.organism.instructions;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class VMStateInstructionTest {

    private World world;
    private Organism org;
    private Simulation sim;
    private final int[] startPos = new int[]{5, 5};

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        world = new World(new int[]{100, 100}, true);
        sim = new Simulation(world);
        org = Organism.create(sim, startPos, 1000, sim.getLogger());
        sim.addOrganism(org);
    }

    private void placeInstruction(String name, Integer... args) {
        int opcode = Instruction.getInstructionIdByName(name);
        world.setSymbol(new Symbol(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        for (int arg : args) {
            currentPos = org.getNextInstructionPosition(currentPos, world, org.getDv());
            world.setSymbol(new Symbol(Config.TYPE_DATA, arg), currentPos);
        }
    }

    // Helper: Instruktion mit Register + Vektor-Argument (z. B. PEKI, SCNI)
    private void placeInstructionWithVector(String name, int reg, int[] vector) {
        int opcode = Instruction.getInstructionIdByName(name);
        world.setSymbol(new Symbol(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        currentPos = org.getNextInstructionPosition(currentPos, world, org.getDv());
        world.setSymbol(new Symbol(Config.TYPE_DATA, reg), currentPos);
        for (int val : vector) {
            currentPos = org.getNextInstructionPosition(currentPos, world, org.getDv());
            world.setSymbol(new Symbol(Config.TYPE_DATA, val), currentPos);
        }
    }

    // Helper: Instruktion mit nur Vektor-Argument (z. B. SEKI)
    private void placeInstructionWithVectorOnly(String name, int[] vector) {
        int opcode = Instruction.getInstructionIdByName(name);
        world.setSymbol(new Symbol(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        for (int val : vector) {
            currentPos = org.getNextInstructionPosition(currentPos, world, org.getDv());
            world.setSymbol(new Symbol(Config.TYPE_DATA, val), currentPos);
        }
    }

    @Test
    void testTurn() {
        int[] vec = new int[]{1, 0};
        org.setDr(0, vec);
        placeInstruction("TURN", 0);
        sim.tick();
        assertThat(org.getDv()).isEqualTo(vec);
    }

    @Test
    void testSync() {
        int[] expected = org.getIp();
        placeInstruction("SYNC");
        sim.tick();
        assertThat(org.getDp()).isEqualTo(expected);
    }

    @Test
    void testNrg() {
        placeInstruction("NRG", 0);
        sim.tick();
        int er = org.getEr();
        int regVal = (Integer) org.getDr(0);
        assertThat(Symbol.fromInt(regVal).toScalarValue()).isEqualTo(er);
    }

    @Test
    void testNrgs() {
        placeInstruction("NRGS");
        sim.tick();
        int val = (Integer) org.getDataStack().pop();
        assertThat(Symbol.fromInt(val).toScalarValue()).isEqualTo(org.getEr());
    }

    @Test
    void testDiff() {
        // dp = ip -> Delta sollte [0,0] sein
        org.setDp(org.getIp());
        placeInstruction("DIFF", 0);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new int[]{0, 0});
    }

    @Test
    void testPos() {
        placeInstruction("POS", 0);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new int[]{0,0}); // Relative to initial position
    }

    @Test
    void testRand() {
        // Obergrenze 10
        org.setDr(0, new Symbol(Config.TYPE_DATA, 10).toInt());
        placeInstruction("RAND", 0);
        sim.tick();
        int val = Symbol.fromInt((Integer) org.getDr(0)).toScalarValue();
        assertThat(val).isGreaterThanOrEqualTo(0).isLessThan(10);
    }

    @Test
    void testSeek() {
        // dp als Ausgangspunkt setzen
        org.setDp(org.getIp());
        int[] vec = new int[]{0, 1};
        org.setDr(0, vec);
        int[] expected = org.getTargetCoordinate(org.getDp(), vec, world);
        placeInstruction("SEEK", 0);
        sim.tick();
        assertThat(org.getDp()).isEqualTo(expected);
    }

    @Test
    void testSeki() {
        org.setDp(org.getIp());
        int[] vec = new int[]{0, 1};
        int[] expected = org.getTargetCoordinate(org.getDp(), vec, world);
        placeInstructionWithVectorOnly("SEKI", vec);
        sim.tick();
        assertThat(org.getDp()).isEqualTo(expected);
    }

    @Test
    void testSeks() {
        org.setDp(org.getIp());
        int[] vec = new int[]{-1, 0};
        int[] expected = org.getTargetCoordinate(org.getDp(), vec, world);
        org.getDataStack().push(vec);
        placeInstruction("SEKS");
        sim.tick();
        assertThat(org.getDp()).isEqualTo(expected);
    }

    @Test
    void testPeek() {
        // Ziel vorbereiten
        org.setDp(org.getIp());
        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(), vec, world);
        int payload = new Symbol(Config.TYPE_DATA, 7).toInt();
        world.setSymbol(Symbol.fromInt(payload), target);

        org.setDr(1, vec); // Vektor in Reg1
        placeInstruction("PEEK", 0, 1);
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(payload);
        // Zelle wurde geleert (optional nicht geprüft)
    }

    @Test
    void testPeki() {
        org.setDp(org.getIp());
        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(), vec, world);
        int payload = new Symbol(Config.TYPE_DATA, 11).toInt();
        world.setSymbol(Symbol.fromInt(payload), target);

        placeInstructionWithVector("PEKI", 0, vec);
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(payload);
    }

    @Test
    void testPeks() {
        org.setDp(org.getIp());
        int[] vec = new int[]{-1, 0};
        int[] target = org.getTargetCoordinate(org.getDp(), vec, world);
        int payload = new Symbol(Config.TYPE_DATA, 9).toInt();
        world.setSymbol(Symbol.fromInt(payload), target);

        org.getDataStack().push(vec);
        placeInstruction("PEKS");
        sim.tick();

        assertThat(org.getDataStack().pop()).isEqualTo(payload);
    }

    @Test
    void testScan() {
        org.setDp(org.getIp());
        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(), vec, world);
        int payload = new Symbol(Config.TYPE_STRUCTURE, 3).toInt();
        world.setSymbol(Symbol.fromInt(payload), target);

        org.setDr(1, vec);
        placeInstruction("SCAN", 0, 1);
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(payload);
        // Zelle bleibt unverändert
        assertThat(world.getSymbol(target).toInt()).isEqualTo(payload);
    }

    @Test
    void testScni() {
        org.setDp(org.getIp());
        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(), vec, world);
        int payload = new Symbol(Config.TYPE_CODE, 42).toInt();
        world.setSymbol(Symbol.fromInt(payload), target);

        placeInstructionWithVector("SCNI", 0, vec);
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(payload);
        assertThat(world.getSymbol(target).toInt()).isEqualTo(payload);
    }

    @Test
    void testScns() {
        org.setDp(org.getIp());
        int[] vec = new int[]{-1, 0};
        int[] target = org.getTargetCoordinate(org.getDp(), vec, world);
        int payload = new Symbol(Config.TYPE_ENERGY, 5).toInt();
        world.setSymbol(Symbol.fromInt(payload), target);

        org.getDataStack().push(vec);
        placeInstruction("SCNS");
        sim.tick();

        assertThat(org.getDataStack().pop()).isEqualTo(payload);
        assertThat(world.getSymbol(target).toInt()).isEqualTo(payload);
    }

    @org.junit.jupiter.api.AfterEach
    void assertNoInstructionFailure() {
        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
    }
}
