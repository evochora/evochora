// src/main/java/org/evochora/Setup.java
package org.evochora;

import java.util.Arrays;

public class Setup {

    public static void run(Simulation simulation) {
        placeInitialOrganism(simulation);
    }

    private static void placeInitialOrganism(Simulation simulation) {
        int[] programCode = {
                Config.OP_SETL,
                0,
                123
        };

        int[] startPos = {Config.WORLD_SHAPE[0] / 2, Config.WORLD_SHAPE[1] / 2};

        int[] currentPos = Arrays.copyOf(startPos, startPos.length);
        for (int symbol : programCode) {
            simulation.getWorld().setSymbol(symbol, currentPos);
            currentPos[0]++;
        }

        // KORRIGIERT: Erstellt den Organismus über die Factory-Methode
        Organism org = Organism.create(simulation, startPos, Config.INITIAL_ORGANISM_ENERGY);
        // Fügt die fertige Instanz zur Simulation hinzu
        simulation.addOrganism(org);
    }
}