package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.Symbol;
import org.evochora.world.World;
// KORRIGIERT: Veralteter Import entfernt
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SetrPrDrMixedTest {

    private World world;
    private Simulation simulation;
    private Organism organism;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setup() {
        world = new World(Config.WORLD_SHAPE, true);
        simulation = new Simulation(world);
        organism = Organism.create(simulation, new int[]{0, 0}, 2000, simulation.getLogger());
    }

    private Object dataVal(int v) { return new Symbol(Config.TYPE_DATA, v).toInt(); }
    private Object vecVal(int x, int y) { return new int[]{x, y}; }

    private void setupSetrInstruction(int destId, int srcId) {
        int setrOpcode = Instruction.getInstructionIdByName("SETR");
        world.setSymbol(new Symbol(Config.TYPE_CODE, setrOpcode), 0, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, destId), 1, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, srcId), 2, 0);
    }

    @Test
    void setr_dr_to_pr_and_back_scalar() {
        // DR0 := DATA:7
        assertTrue(organism.setDr(0, dataVal(7)));

        // PR0 := DR0
        setupSetrInstruction(1000, 0); // 1000 kodiert PR0 als Ziel
        Instruction i1 = organism.planTick(world);
        i1.execute(simulation);

        // DR1 := PR0
        setupSetrInstruction(1, 1000); // 1000 kodiert PR0 als Quelle
        Instruction i2 = organism.planTick(world);
        i2.execute(simulation);

        Object dr1 = organism.getDr(1);
        assertTrue(dr1 instanceof Integer);
        assertEquals(7, Symbol.fromInt((Integer) dr1).toScalarValue());
        assertEquals(Config.TYPE_DATA, Symbol.fromInt((Integer) dr1).type());
    }

    @Test
    void setr_dr_to_pr_and_back_vector() {
        assertTrue(organism.setDr(2, vecVal(3, 4)));

        // PR1 := DR2
        setupSetrInstruction(1001, 2); // 1001 kodiert PR1 als Ziel
        Instruction i1 = organism.planTick(world);
        i1.execute(simulation);

        // DR3 := PR1
        setupSetrInstruction(3, 1001); // 1001 kodiert PR1 als Quelle
        Instruction i2 = organism.planTick(world);
        i2.execute(simulation);

        Object dr3 = organism.getDr(3);
        assertTrue(dr3 instanceof int[]);
        int[] v = (int[]) dr3;
        assertArrayEquals(new int[]{3,4}, v);
    }

    @Test
    void setr_pr_to_pr_scalar() {
        // Lege einen Skalar in PR0 ab, indem wir ihn erst in DR0 legen und dann kopieren
        assertTrue(organism.setDr(0, dataVal(42)));
        setupSetrInstruction(1000, 0);
        Instruction toPr0 = organism.planTick(world);
        toPr0.execute(simulation);

        // PR1 := PR0
        setupSetrInstruction(1001, 1000);
        Instruction prToPr = organism.planTick(world);
        prToPr.execute(simulation);

        // Kopiere PR1 zurück nach DR4 und überprüfe
        setupSetrInstruction(4, 1001);
        Instruction back = organism.planTick(world);
        back.execute(simulation);

        Object dr4 = organism.getDr(4);
        assertTrue(dr4 instanceof Integer);
        assertEquals(42, Symbol.fromInt((Integer) dr4).toScalarValue());
    }
}
