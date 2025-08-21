package org.evochora.runtime.worldgen;

import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import java.util.Random;

public class SolarRadiationCreator implements IEnergyDistributionCreator {

    private final Random random = new Random();
    private final double spawnProbability;
    private final int spawnAmount;
    private final int safetyRadius;

    public SolarRadiationCreator(double probability, int amount, int safetyRadius) {
        this.spawnProbability = probability;
        this.spawnAmount = amount;
        this.safetyRadius = safetyRadius;
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