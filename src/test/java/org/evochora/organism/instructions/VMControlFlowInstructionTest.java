package org.evochora.organism.instructions;

import org.evochora.app.setup.Config;
import org.evochora.app.Simulation;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Symbol;
import org.evochora.runtime.model.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class VMControlFlowInstructionTest {

    private World world;
    private Organism org;
    private Simulation sim;
    private final int[] startPos = new int[]{5};

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        world = new World(new int[]{100}, true);
        sim = new Simulation(world);
        org = Organism.create(sim, startPos, 1000, sim.getLogger());
        sim.addOrganism(org);
    }

    private void placeInstructionWithVector(String name, int[] vector) {
        int opcode = Instruction.getInstructionIdByName(name);
        world.setSymbol(new Symbol(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        for (int val : vector) {
            currentPos = org.getNextInstructionPosition(currentPos, world, org.getDv());
            world.setSymbol(new Symbol(Config.TYPE_DATA, val), currentPos);
        }
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

    @Test
    void testJmpi() {
        int[] jumpDelta = new int[]{10};
        int[] expectedIp = org.getTargetCoordinate(org.getIp(), jumpDelta, world);
        placeInstructionWithVector("JMPI", jumpDelta);

        sim.tick();

        assertThat(org.getIp()).isEqualTo(expectedIp);
    }

    @Test
    void testCall() {
        int[] jumpDelta = new int[]{7};
        int[] expectedIp = org.getTargetCoordinate(org.getIp(), jumpDelta, world);
        placeInstructionWithVector("CALL", jumpDelta);

        sim.tick();

        assertThat(org.getIp()).isEqualTo(expectedIp);
        assertThat(org.getCallStack().peek()).isInstanceOf(Organism.ProcFrame.class);
    }

    @Test
    void testJmpr() {
        // JMPR interpretiert den Operanden als relative Koordinate zum initialen Start
        int[] relative = new int[]{12};
        int[] expectedIp = new int[]{org.getInitialPosition()[0] + relative[0]};
        org.setDr(0, relative);
        placeInstruction("JMPR", 0);

        sim.tick();

        assertThat(org.getIp()).isEqualTo(expectedIp);
    }

    @Test
    void testJmps() {
        int[] jumpDelta = new int[]{8};
        int[] expectedIp = org.getTargetCoordinate(org.getIp(), jumpDelta, world);
        // Vektor oben auf den Stack legen
        org.getDataStack().push(jumpDelta);
        placeInstruction("JMPS");

        sim.tick();

        assertThat(org.getIp()).isEqualTo(expectedIp);
    }

        @org.junit.jupiter.api.AfterEach
        void assertNoInstructionFailure() {
            assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
        }

    @Test
    void testRet() {
        // Einen Return-Frame vorbereiten, der auf InitialPosition + 3 zurückspringt
        int[] relativeReturn = new int[]{3};
        int[] expectedIp = new int[]{org.getInitialPosition()[0] + relativeReturn[0]};
        Object[] prsSnapshot = org.getPrs().toArray(new Object[0]);
        // KORREKTUR: Verwende den neuen Konstruktor und den Call-Stack
        Object[] fprsSnapshot = org.getFprs().toArray(new Object[0]);
        org.getCallStack().push(new Organism.ProcFrame("TEST_PROC", relativeReturn, prsSnapshot, fprsSnapshot, java.util.Collections.emptyMap()));

        placeInstruction("RET");

        sim.tick();

        assertThat(org.getIp()).isEqualTo(expectedIp);
    }
}
