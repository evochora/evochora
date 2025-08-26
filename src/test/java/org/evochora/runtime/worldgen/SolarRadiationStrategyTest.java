package org.evochora.runtime.worldgen;

import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class SolarRadiationStrategyTest {

    @Test
    @Tag("unit")
    void placesEnergyInNDEnvironment_whenAreaIsUnowned() {
        // 3D world with a single cell ensures deterministic coordinate selection
        Environment env = new Environment(new int[]{1, 1, 1}, true);
        SolarRadiationCreator strat = new SolarRadiationCreator(1.0, 42, 0);

        strat.distributeEnergy(env, 1);

        Molecule cell = env.getMolecule(0, 0, 0);
        assertThat(cell.type()).isEqualTo(Config.TYPE_ENERGY);
        assertThat(cell.toScalarValue()).isEqualTo(42);
    }

    @Test
    @Tag("unit")
    void respectsSafetyRadius_andDoesNotPlaceEnergyNearOwnedCells() {
        // 3D world with single cell; mark it as owned to violate safety radius
        Environment env = new Environment(new int[]{1, 1, 1}, true);
        env.setOwnerId(7, 0, 0, 0);
        SolarRadiationCreator strat = new SolarRadiationCreator(1.0, 99, 1);

        strat.distributeEnergy(env, 1);

        Molecule cell = env.getMolecule(0, 0, 0);
        // remains empty (CODE:0) because area is not unowned
        assertThat(cell.isEmpty()).isTrue();
    }
}


