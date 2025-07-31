// src/main/java/org/evochora/organism/actions/DiffAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;

public class DiffAction extends Action {
    private final int reg;

    public DiffAction(Organism organism, int reg) {
        super(organism);
        this.reg = reg;
    }

    public static Action plan(Organism organism, World world) {
        return new DiffAction(organism, organism.fetchArgument(world));
    }

    @Override
    public void execute(Simulation simulation) {
        // Berechnet den Vektor zwischen der aktuellen und der letzten IP-Position
        int[] currentIp = organism.getIpBeforeFetch(); // Nutzt die Position VOR dem Argument-Fetch
        int[] lastIp = organism.getLastIp();

        int[] delta = new int[currentIp.length];
        for (int i = 0; i < currentIp.length; i++) {
            delta[i] = currentIp[i] - lastIp[i];
        }

        // Speichert das Ergebnis im Ziel-Register
        if (!organism.setDr(reg, delta)) {
            organism.instructionFailed();
        }
    }
}