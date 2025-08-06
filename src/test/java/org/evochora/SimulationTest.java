// src/test/java/org/evochora/SimulationTest.java
package org.evochora;

import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.organism.PokeInstruction;
import org.evochora.organism.SetiInstruction;
import org.evochora.organism.SetvInstruction;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.junit.jupiter.api.*;

import java.util.Arrays;

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

    @Disabled("Dieser test ist zu komplex und nicht richtig ausgesetzt.")
    @Test
    void testConflictResolution_LowerIdWins() {
        // Platziere die SETI und SETV Anweisungen für Organismus 1
        // SETI hat eine Länge von 3, SETV hat eine Länge von 4
        world.setSymbol(new Symbol(Config.TYPE_CODE, SetiInstruction.ID), 0, 0); // SETI %DR0 DATA:100
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 1, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, new Symbol(Config.TYPE_DATA, 100).toInt()), 2, 0);

        world.setSymbol(new Symbol(Config.TYPE_CODE, SetvInstruction.ID), 3, 0); // SETV %DR1 0|9
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 4, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 5, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 9), 6, 0);

        // Platziere die POKE Anweisung für Organismus 1. Beginnend an 7,0
        // (0,0) + 3 + 4 = 7
        world.setSymbol(new Symbol(Config.TYPE_CODE, PokeInstruction.ID), 7, 0); // POKE %DR0 %DR1
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 8, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 9, 0);

        // Platziere die SETI und SETV Anweisungen für Organismus 2
        world.setSymbol(new Symbol(Config.TYPE_CODE, SetiInstruction.ID), 1, 1); // SETI %DR0 DATA:200
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 2, 1);
        world.setSymbol(new Symbol(Config.TYPE_DATA, new Symbol(Config.TYPE_DATA, 200).toInt()), 3, 1);

        world.setSymbol(new Symbol(Config.TYPE_CODE, SetvInstruction.ID), 4, 1); // SETV %DR1 -1|8
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 5, 1);
        world.setSymbol(new Symbol(Config.TYPE_DATA, -1), 6, 1);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 8), 7, 1);

        // Platziere die POKE Anweisung für Organismus 2. Beginnend an 8,1
        // (1,1) + 3 + 4 = 8,1
        world.setSymbol(new Symbol(Config.TYPE_CODE, PokeInstruction.ID), 8, 1); // POKE %DR0 %DR1
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 9, 1);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 0, 2);


        // Simuliere 2 Ticks, um die SETI- und SETV-Anweisungen auszuführen
        simulation.tick();
        simulation.tick();

        // Der nächste Tick löst den Konflikt aus
        simulation.tick();

        // Das POKE von Organismus 1 hat die Ziel-Koordinate: dp(0,0) + vec(0,9) -> (0,9)
        // Das POKE von Organismus 2 hat die Ziel-Koordinate: dp(1,1) + vec(-1,8) -> (0,9)
        // Der Organismus mit der niedrigeren ID (ID=0) gewinnt.
        Assertions.assertEquals(100, world.getSymbol(0, 9).toScalarValue());
        Assertions.assertTrue(world.getSymbol(0, 9).type() == Config.TYPE_DATA);
    }
}