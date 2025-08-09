package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.evochora.organism.instructions.AddsSInstruction;
import org.evochora.organism.instructions.SubsSInstruction;
import org.evochora.organism.instructions.AndsSInstruction;
import org.evochora.organism.instructions.NotsSInstruction;
import org.evochora.organism.instructions.ShlsSInstruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Deque;

import static org.junit.jupiter.api.Assertions.*;

public class SVariantsArithmeticTest {

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
    void adds_simple_scalars() {
        int addsId = Instruction.getInstructionIdByName("ADDS");
        world.setSymbol(new Symbol(Config.TYPE_CODE, addsId), 0, 0);
        Deque<Object> ds = organism.getDataStack();
        ds.push(new Symbol(Config.TYPE_DATA, 3).toInt());
        ds.push(new Symbol(Config.TYPE_DATA, 4).toInt());
        Instruction adds = AddsSInstruction.plan(organism, world);
        adds.execute(simulation);
        assertFalse(organism.isInstructionFailed());
        Object res = ds.pop();
        assertTrue(res instanceof Integer);
        assertEquals(7, Symbol.fromInt((Integer) res).toScalarValue());
    }

    @Test
    void subs_simple_scalars() {
        int subsId = Instruction.getInstructionIdByName("SUBS");
        world.setSymbol(new Symbol(Config.TYPE_CODE, subsId), 0, 0);
        Deque<Object> ds = organism.getDataStack();
        ds.push(new Symbol(Config.TYPE_DATA, 10).toInt());
        ds.push(new Symbol(Config.TYPE_DATA, 4).toInt());
        Instruction subs = SubsSInstruction.plan(organism, world);
        subs.execute(simulation);
        assertFalse(organism.isInstructionFailed());
        Object res = ds.pop();
        assertEquals(6, Symbol.fromInt((Integer) res).toScalarValue());
    }

    @Test
    void ands_simple_scalars() {
        int andsId = Instruction.getInstructionIdByName("ANDS");
        world.setSymbol(new Symbol(Config.TYPE_CODE, andsId), 0, 0);
        Deque<Object> ds = organism.getDataStack();
        ds.push(new Symbol(Config.TYPE_DATA, 0b1100).toInt());
        ds.push(new Symbol(Config.TYPE_DATA, 0b1010).toInt());
        Instruction ands = AndsSInstruction.plan(organism, world);
        ands.execute(simulation);
        assertFalse(organism.isInstructionFailed());
        Object res = ds.pop();
        assertEquals(0b1000, Symbol.fromInt((Integer) res).toScalarValue());
    }

    @Test
    void nots_simple_scalar() {
        int notsId = Instruction.getInstructionIdByName("NOTS");
        world.setSymbol(new Symbol(Config.TYPE_CODE, notsId), 0, 0);
        Deque<Object> ds = organism.getDataStack();
        ds.push(new Symbol(Config.TYPE_DATA, 0).toInt());
        Instruction nots = NotsSInstruction.plan(organism, world);
        nots.execute(simulation);
        assertFalse(organism.isInstructionFailed());
        Object res = ds.pop();
        // ~0 == -1 in two's complement
        assertEquals(-1, Symbol.fromInt((Integer) res).toScalarValue());
    }

    @Test
    void shls_value_by_2() {
        int shlsId = Instruction.getInstructionIdByName("SHLS");
        world.setSymbol(new Symbol(Config.TYPE_CODE, shlsId), 0, 0);
        Deque<Object> ds = organism.getDataStack();
        ds.push(new Symbol(Config.TYPE_DATA, 2).toInt());   // count
        ds.push(new Symbol(Config.TYPE_DATA, 3).toInt());   // value
        Instruction shls = ShlsSInstruction.plan(organism, world);
        shls.execute(simulation);
        assertFalse(organism.isInstructionFailed());
        Object res = ds.pop();
        assertEquals(12, Symbol.fromInt((Integer) res).toScalarValue()); // 3<<2 = 12
    }
}
