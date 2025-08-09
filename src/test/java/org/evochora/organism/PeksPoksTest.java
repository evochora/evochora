package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.evochora.organism.instructions.PeksInstruction;
import org.evochora.organism.instructions.PoksInstruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Deque;

import static org.junit.jupiter.api.Assertions.*;

public class PeksPoksTest {

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
        organism = Organism.create(simulation, new int[]{5, 5}, 1000, simulation.getLogger());
    }

    @Test
    void peks_pushes_symbol_and_clears_cell_energy_awards() {
        int peksId = Instruction.getInstructionIdByName("PEKS");
        world.setSymbol(new Symbol(Config.TYPE_CODE, peksId), 5, 5);
        // place ENERGY:10 at DP+vec (0,1)
        int[] vec = new int[]{0, 1};
        int[] target = organism.getTargetCoordinate(organism.getDp(), vec, world);
        world.setSymbol(new Symbol(Config.TYPE_ENERGY, 10), target);

        Deque<Object> ds = organism.getDataStack();
        ds.push(vec);
        Instruction peks = PeksInstruction.plan(organism, world);
        peks.execute(simulation);

        assertFalse(organism.isInstructionFailed());
        Object top = ds.pop();
        assertTrue(top instanceof Integer);
        assertEquals(Config.TYPE_ENERGY, Symbol.fromInt((Integer) top).type());
        assertEquals(10, Symbol.fromInt((Integer) top).toScalarValue());
        // cleared
        assertEquals(Config.TYPE_CODE, world.getSymbol(target).type());
    }

    @Test
    void poks_writes_symbol_from_stack() {
        int poksId = Instruction.getInstructionIdByName("POKS");
        world.setSymbol(new Symbol(Config.TYPE_CODE, poksId), 5, 5);

        int[] vec = new int[]{1, 0};
        int[] target = organism.getTargetCoordinate(organism.getDp(), vec, world);
        Deque<Object> ds = organism.getDataStack();
        // push value then vec so vec is TOS
        ds.push(new Symbol(Config.TYPE_DATA, 42).toInt());
        ds.push(vec);

        Instruction poks = PoksInstruction.plan(organism, world);
        poks.execute(simulation);
        assertFalse(organism.isInstructionFailed());
        Symbol written = world.getSymbol(target);
        assertEquals(Config.TYPE_DATA, written.type());
        assertEquals(42, written.toScalarValue());
    }
}
