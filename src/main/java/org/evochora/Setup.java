// src/main/java/org/evochora/Setup.java
package org.evochora;

import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import java.util.Arrays;

public class Setup {

    public static void run(Simulation simulation) {
        placeInitialOrganism(simulation);
    }

    private static void placeInitialOrganism(Simulation simulation) {
        // Definiere den Maschinencode als Symbol-Objekte
        Symbol[] programCode = {
                new Symbol(Config.TYPE_CODE, 1), // SETL Opcode
                new Symbol(Config.TYPE_DATA, 0), // Argument 1: Register Index (DR0)
                new Symbol(Config.TYPE_DATA, 123) // Argument 2: Literal-Wert
        };

        int[] startPos = {Config.WORLD_SHAPE[0] / 2, Config.WORLD_SHAPE[1] / 2};
        World world = simulation.getWorld();

        int[] currentPos = Arrays.copyOf(startPos, startPos.length);
        for (Symbol symbol : programCode) {
            world.setSymbol(symbol, currentPos);
            currentPos[0]++;
        }

        Organism org = Organism.create(simulation, startPos, Config.INITIAL_ORGANISM_ENERGY);
        simulation.addOrganism(org);
    }
}