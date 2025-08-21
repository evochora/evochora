package org.evochora.runtime.worldgen;

import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import java.util.Random;
import org.evochora.runtime.internal.services.IRandomProvider;

public class SolarRadiationCreator implements IEnergyDistributionCreator {

    private final Random random;
    private final double spawnProbability;
    private final int spawnAmount;
    private final int safetyRadius;

    public SolarRadiationCreator(IRandomProvider randomProvider, double probability, int amount, int safetyRadius) {
        this.random = randomProvider.asJavaRandom();
        this.spawnProbability = probability;
        this.spawnAmount = amount;
        this.safetyRadius = safetyRadius;
    }

    // Backward-compatible constructor for tests and legacy code
    public SolarRadiationCreator(double probability, int amount, int safetyRadius) {
        this(new org.evochora.runtime.internal.services.SeededRandomProvider(0L), probability, amount, safetyRadius);
    }

    @Override
    public void distributeEnergy(Environment environment, long currentTick) {
        if (random.nextDouble() < this.spawnProbability) {
            int[] shape = environment.getShape();
            int[] coord = new int[shape.length];
            for (int i = 0; i < shape.length; i++) {
                coord[i] = random.nextInt(shape[i]);
            }

            // Prüfe, ob die Zelle leer ist UND der Bereich um sie herum unbeansprucht ist.
            if (environment.getMolecule(coord).isEmpty() && environment.isAreaUnowned(coord, this.safetyRadius)) {
                environment.setMolecule(new Molecule(Config.TYPE_ENERGY, spawnAmount), coord);
            }
        }
    }
}