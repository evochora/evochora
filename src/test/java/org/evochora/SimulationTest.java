package org.evochora;

import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.junit.jupiter.api.*;

public class SimulationTest {

    private World world;
    private Simulation simulation;
    private Organism organism1;
    private Organism organism2;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setup() {
        Config.WORLD_SHAPE[0] = 10;
        Config.WORLD_SHAPE[1] = 10;
        world = new World(Config.WORLD_SHAPE, true);
        simulation = new Simulation(world);

        organism1 = Organism.create(simulation, new int[]{0, 0}, 2000, simulation.getLogger());
        simulation.addOrganism(organism1);
        organism2 = Organism.create(simulation, new int[]{1, 1}, 2000, simulation.getLogger());
        simulation.addOrganism(organism2);
    }

    private int getOpcode(String name) {
        return Instruction.getInstructionIdByName(name);
    }

    @Disabled("Dieser Test ist zu komplex und muss an die neue Architektur angepasst werden.")
    @Test
    void testConflictResolution_LowerIdWins() {
        // KORREKTUR: Die IDs werden jetzt über die zentrale Registrierung geholt.
        world.setSymbol(new Symbol(Config.TYPE_CODE, getOpcode("SETI")), 0, 0); // SETI %DR0 DATA:100
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 1, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, new Symbol(Config.TYPE_DATA, 100).toInt()), 2, 0);

        world.setSymbol(new Symbol(Config.TYPE_CODE, getOpcode("SETV")), 3, 0); // SETV %DR1 0|9
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 4, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 5, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 9), 6, 0);

        world.setSymbol(new Symbol(Config.TYPE_CODE, getOpcode("POKE")), 7, 0); // POKE %DR0 %DR1
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 8, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 9, 0);

        world.setSymbol(new Symbol(Config.TYPE_CODE, getOpcode("SETI")), 1, 1); // SETI %DR0 DATA:200
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 2, 1);
        world.setSymbol(new Symbol(Config.TYPE_DATA, new Symbol(Config.TYPE_DATA, 200).toInt()), 3, 1);

        world.setSymbol(new Symbol(Config.TYPE_CODE, getOpcode("SETV")), 4, 1); // SETV %DR1 -1|8
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 5, 1);
        world.setSymbol(new Symbol(Config.TYPE_DATA, -1), 6, 1);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 8), 7, 1);

        world.setSymbol(new Symbol(Config.TYPE_CODE, getOpcode("POKE")), 8, 1); // POKE %DR0 %DR1
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 9, 1);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 0, 2);


        // Simuliere 2 Ticks, um die SETI- und SETV-Anweisungen auszuführen
        simulation.tick();
        simulation.tick();

        // Der nächste Tick löst den Konflikt aus
        simulation.tick();

        Assertions.assertEquals(100, world.getSymbol(0, 9).toScalarValue());
        Assertions.assertTrue(world.getSymbol(0, 9).type() == Config.TYPE_DATA);
    }
}