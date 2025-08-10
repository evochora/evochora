package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.evochora.organism.instructions.ConditionalInstruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Deque;

import static org.junit.jupiter.api.Assertions.*;

public class SVariantsConditionsTest {

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

    @Test
    void ifs_true_no_skip_false_skips_next() {
        int ifsId = Instruction.getInstructionIdByName("IFS");
        int nopId = Instruction.getInstructionIdByName("NOP");
        world.setSymbol(new Symbol(Config.TYPE_CODE, ifsId), 0, 0);
        world.setSymbol(new Symbol(Config.TYPE_CODE, nopId), 1, 0); // next

        // true: DATA:3 == DATA:3
        Deque<Object> ds = organism.getDataStack();
        ds.push(new Symbol(Config.TYPE_DATA, 3).toInt());
        ds.push(new Symbol(Config.TYPE_DATA, 3).toInt());
        Instruction ifs = ConditionalInstruction.plan(organism, world);
        ifs.execute(simulation);
        assertFalse(organism.isInstructionFailed());
        assertArrayEquals(new int[]{0,0}, organism.getIp(), "IP unchanged on true");

        // reset organism for false branch
        organism = Organism.create(simulation, new int[]{0, 0}, 2000, simulation.getLogger());
        world.setSymbol(new Symbol(Config.TYPE_CODE, ifsId), 0, 0);
        world.setSymbol(new Symbol(Config.TYPE_CODE, nopId), 1, 0);
        ds = organism.getDataStack();
        ds.push(new Symbol(Config.TYPE_DATA, 1).toInt());
        ds.push(new Symbol(Config.TYPE_DATA, 2).toInt());
        ifs = ConditionalInstruction.plan(organism, world);
        ifs.execute(simulation);
        assertArrayEquals(new int[]{2,0}, organism.getIp(), "IP should skip next instruction on false");
    }

    @Test
    void gts_lts_work_on_scalars() {
        int gtsId = Instruction.getInstructionIdByName("GTS");
        int ltsId = Instruction.getInstructionIdByName("LTS");
        int nopId = Instruction.getInstructionIdByName("NOP");

        // GTS true: 5 > 3
        organism = Organism.create(simulation, new int[]{0, 0}, 2000, simulation.getLogger());
        world.setSymbol(new Symbol(Config.TYPE_CODE, gtsId), 0, 0);
        world.setSymbol(new Symbol(Config.TYPE_CODE, nopId), 1, 0);
        Deque<Object> ds = organism.getDataStack();
        ds.push(new Symbol(Config.TYPE_DATA, 3).toInt());
        ds.push(new Symbol(Config.TYPE_DATA, 5).toInt());
        Instruction gts = ConditionalInstruction.plan(organism, world);
        gts.execute(simulation);
        assertArrayEquals(new int[]{0,0}, organism.getIp());

        // LTS false: 7 < 4 -> false, skip next
        organism = Organism.create(simulation, new int[]{0, 0}, 2000, simulation.getLogger());
        world.setSymbol(new Symbol(Config.TYPE_CODE, ltsId), 0, 0);
        world.setSymbol(new Symbol(Config.TYPE_CODE, nopId), 1, 0);
        ds = organism.getDataStack();
        ds.push(new Symbol(Config.TYPE_DATA, 4).toInt());
        ds.push(new Symbol(Config.TYPE_DATA, 7).toInt());
        Instruction lts = ConditionalInstruction.plan(organism, world);
        lts.execute(simulation);
        assertArrayEquals(new int[]{2,0}, organism.getIp());
    }

    @Test
    void ifts_true_on_same_type_false_else() {
        int iftsId = Instruction.getInstructionIdByName("IFTS");
        int nopId = Instruction.getInstructionIdByName("NOP");

        // true: DATA vs DATA
        organism = Organism.create(simulation, new int[]{0, 0}, 2000, simulation.getLogger());
        world.setSymbol(new Symbol(Config.TYPE_CODE, iftsId), 0, 0);
        world.setSymbol(new Symbol(Config.TYPE_CODE, nopId), 1, 0);
        Deque<Object> ds = organism.getDataStack();
        ds.push(new Symbol(Config.TYPE_DATA, 1).toInt());
        ds.push(new Symbol(Config.TYPE_DATA, 2).toInt());
        Instruction ifts = ConditionalInstruction.plan(organism, world);
        ifts.execute(simulation);
        assertArrayEquals(new int[]{0,0}, organism.getIp());

        // false: DATA vs CODE -> skip next
        organism = Organism.create(simulation, new int[]{0, 0}, 2000, simulation.getLogger());
        world.setSymbol(new Symbol(Config.TYPE_CODE, iftsId), 0, 0);
        world.setSymbol(new Symbol(Config.TYPE_CODE, nopId), 1, 0);
        ds = organism.getDataStack();
        ds.push(new Symbol(Config.TYPE_DATA, 1).toInt());
        ds.push(new Symbol(Config.TYPE_CODE, 0).toInt());
        ifts = ConditionalInstruction.plan(organism, world);
        ifts.execute(simulation);
        assertArrayEquals(new int[]{2,0}, organism.getIp());
    }
}
