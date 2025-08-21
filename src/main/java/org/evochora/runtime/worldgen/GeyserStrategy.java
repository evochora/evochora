package org.evochora.runtime.worldgen;

import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class GeyserStrategy implements IEnergyDistributionStrategy {

    private final int geyserCount;
    private final int tickInterval;
    private final int energyAmount;
    private final Random random = new Random();
    private List<int[]> geyserLocations = null; // Wird beim ersten Aufruf initialisiert

    public GeyserStrategy(int count, int interval, int amount) {
        this.geyserCount = count;
        this.tickInterval = interval;
        this.energyAmount = amount;
    }

    @Override
    public void distributeEnergy(Environment environment, long currentTick) {
        if (geyserLocations == null) {
            initializeGeysers(environment);
        }

        if (currentTick > 0 && currentTick % tickInterval == 0) {
            for (int[] geyserPos : geyserLocations) {
                // Finde alle gültigen Nachbarzellen (leer und unbeansprucht)
                List<int[]> validTargets = new ArrayList<>();
                int[][] neighbors = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}}; // Für 2D
                for (int[] offset : neighbors) {
                    int[] checkPos = {geyserPos[0] + offset[0], geyserPos[1] + offset[1]};
                    if (environment.getMolecule(checkPos).isEmpty() && environment.getOwnerId(checkPos) == 0) {
                        validTargets.add(checkPos);
                    }
                }

                // Wähle ein zufälliges gültiges Ziel und platziere die Energie
                if (!validTargets.isEmpty()) {
                    Collections.shuffle(validTargets, random);
                    int[] targetCell = validTargets.get(0);
                    environment.setMolecule(new Molecule(Config.TYPE_ENERGY, energyAmount), targetCell);
                }
            }
        }
    }

    private void initializeGeysers(Environment environment) {
        geyserLocations = new ArrayList<>();
        int[] shape = environment.getShape();
        for (int i = 0; i < geyserCount; i++) {
            int[] coord = new int[shape.length];
            for (int d = 0; d < shape.length; d++) {
                coord[d] = random.nextInt(shape[d]);
            }
            geyserLocations.add(coord);
            // Markiere die Quelle selbst als unzerstörbar, um Konflikte zu vermeiden
            environment.setMolecule(new Molecule(Config.TYPE_STRUCTURE, -1), coord);
        }
    }
}