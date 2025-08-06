// src/test/java/org/evochora/SimulationTest.java
package org.evochora;

import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.organism.PokeInstruction;
import org.evochora.organism.SetiInstruction;
import org.evochora.organism.SetvInstruction;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

    @Test
    void testConflictResolution_LowerIdWins() {
        // Der Testcode wird direkt in die Welt platziert, um die Register der Organismen zu setzen.
        // Organismus 1 (ID=0) soll DR0=100 und DR1=[0,1] haben.
        world.setSymbol(new Symbol(Config.TYPE_CODE, SetiInstruction.ID), 0, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 0, 1);
        world.setSymbol(new Symbol(Config.TYPE_DATA, new Symbol(Config.TYPE_DATA, 100).toInt()), 0, 2);

        world.setSymbol(new Symbol(Config.TYPE_CODE, SetvInstruction.ID), 0, 3);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 0, 4);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 0, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 0, 6);

        // Organismus 2 (ID=1) soll DR0=200 und DR1=[-1,0] haben.
        world.setSymbol(new Symbol(Config.TYPE_CODE, SetiInstruction.ID), 1, 1);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 1, 2);
        world.setSymbol(new Symbol(Config.TYPE_DATA, new Symbol(Config.TYPE_DATA, 200).toInt()), 1, 3);

        world.setSymbol(new Symbol(Config.TYPE_CODE, SetvInstruction.ID), 1, 4);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 1, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, -1), 1, 6);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 1, 7);

        // Die eigentlichen POKE-Befehle, die den Konflikt auslösen.
        world.setSymbol(new Symbol(Config.TYPE_CODE, PokeInstruction.ID), 0, 7);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 0, 8);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 0, 9);
        world.setSymbol(new Symbol(Config.TYPE_CODE, PokeInstruction.ID), 1, 8);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 1, 9);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 1, 10);

        // Simuliere 3 Ticks, um alle SETI/SETV-Befehle auszuführen
        simulation.tick();
        simulation.tick();
        simulation.tick();

        // Nun befinden sich beide Organismen an den Startpositionen ihrer POKE-Befehle
        // und haben die korrekten Werte in ihren Registern. Führe den Konflikt-Tick aus.
        simulation.tick();

        // Überprüfung, dass die Aktion von Organismus 1 ausgeführt wurde, da ID=0.
        Assertions.assertEquals(100, world.getSymbol(0, 1).toScalarValue());
        Assertions.assertTrue(world.getSymbol(0, 1).type() == Config.TYPE_DATA);

        // Überprüfung, dass die Aktion von Organismus 2 nicht ausgeführt wurde, da ID=1.
        Assertions.assertNotEquals(200, world.getSymbol(0, 1).toScalarValue());
    }
}