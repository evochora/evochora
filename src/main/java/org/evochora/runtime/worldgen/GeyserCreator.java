package org.evochora.runtime.worldgen;

import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.evochora.runtime.internal.services.IRandomProvider;

public class GeyserCreator implements IEnergyDistributionCreator {

    private final int geyserCount;
    private final int tickInterval;
    private final int energyAmount;
    private final Random random;
    private List<int[]> geyserLocations = null; // Wird beim ersten Aufruf initialisiert

    public GeyserCreator(IRandomProvider randomProvider, int count, int interval, int amount) {
        this.random = randomProvider.asJavaRandom();
        this.geyserCount = count;
        this.tickInterval = interval;
        this.energyAmount = amount;
    }

    // Backward-compatible constructor for legacy code/tests
    public GeyserCreator(int count, int interval, int amount) {
        this(new org.evochora.runtime.internal.services.SeededRandomProvider(0L), count, interval, amount);
    }

    @Override
    public void distributeEnergy(Environment environment, long currentTick) {
        if (geyserLocations == null) {
            initializeGeysers(environment);
        }

        if (currentTick > 0 && currentTick % tickInterval == 0) {
            for (int[] geyserPos : geyserLocations) {
                // Finde alle gültigen Nachbarzellen (leer und unbeansprucht) in N Dimensionen
                List<int[]> validTargets = new ArrayList<>();
                int dims = environment.getShape().length;
                for (int axis = 0; axis < dims; axis++) {
                    for (int delta : new int[]{-1, 1}) {
                        int[] checkPos = java.util.Arrays.copyOf(geyserPos, dims);
                        checkPos[axis] = checkPos[axis] + delta;
                        if (environment.getMolecule(checkPos).isEmpty() && environment.getOwnerId(checkPos) == 0) {
                            validTargets.add(checkPos);
                        }
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