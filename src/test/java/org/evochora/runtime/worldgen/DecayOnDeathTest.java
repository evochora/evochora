package org.evochora.runtime.worldgen;

import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.spi.DeathContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DecayOnDeath}.
 */
class DecayOnDeathTest {

    private Environment environment;
    private DeathContext context;

    @BeforeEach
    void setUp() {
        environment = new Environment(new int[]{10, 10}, true);
        context = new DeathContext();
    }

    @Test
    @Tag("unit")
    void convertsAllOwnedMoleculesToConfiguredReplacement() {
        // Setup: organism 1 owns multiple cells with different molecule types
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 42), 1, new int[]{1, 1});
        environment.setMolecule(new Molecule(Config.TYPE_STRUCTURE, 100), 1, new int[]{2, 2});
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 200), 1, new int[]{3, 3});

        // Handler that converts to ENERGY:1000
        DecayOnDeath handler = new DecayOnDeath(new Molecule(Config.TYPE_ENERGY, 1000));

        context.reset(environment, 1);
        handler.onDeath(context);

        // All molecules should now be energy with 1000 value
        assertThat(environment.getMolecule(1, 1).type()).isEqualTo(Config.TYPE_ENERGY);
        assertThat(environment.getMolecule(1, 1).value()).isEqualTo(1000);

        assertThat(environment.getMolecule(2, 2).type()).isEqualTo(Config.TYPE_ENERGY);
        assertThat(environment.getMolecule(2, 2).value()).isEqualTo(1000);

        assertThat(environment.getMolecule(3, 3).type()).isEqualTo(Config.TYPE_ENERGY);
        assertThat(environment.getMolecule(3, 3).value()).isEqualTo(1000);
    }

    @Test
    @Tag("unit")
    void canClearCellsWithCodeZero() {
        // Setup: organism 1 owns a cell
        environment.setMolecule(new Molecule(Config.TYPE_STRUCTURE, 500), 1, new int[]{5, 5});

        // Handler that converts to CODE:0 (effectively clears the cell)
        DecayOnDeath handler = new DecayOnDeath(new Molecule(Config.TYPE_CODE, 0));

        context.reset(environment, 1);
        handler.onDeath(context);

        // Cell should now be CODE:0 which is isEmpty()
        Molecule result = environment.getMolecule(5, 5);
        assertThat(result.type()).isEqualTo(Config.TYPE_CODE);
        assertThat(result.value()).isEqualTo(0);
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    @Tag("unit")
    void ignoresEmptyCells() {
        // Setup: organism 1 owns an empty cell (just ownership, no molecule)
        environment.setOwnerId(1, 5, 5);

        // Also set a non-empty cell for comparison
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 1), 1, new int[]{6, 6});

        DecayOnDeath handler = new DecayOnDeath(new Molecule(Config.TYPE_ENERGY, 100));

        context.reset(environment, 1);
        handler.onDeath(context);

        // Empty cell should remain empty
        assertThat(environment.getMolecule(5, 5).isEmpty()).isTrue();

        // Non-empty cell should be converted
        assertThat(environment.getMolecule(6, 6).type()).isEqualTo(Config.TYPE_ENERGY);
    }

    @Test
    @Tag("unit")
    void doesNotAffectOtherOrganismsCells() {
        // Organism 1's cells
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 10), 1, new int[]{0, 0});

        // Organism 2's cells - should not be affected
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 20), 2, new int[]{9, 9});

        DecayOnDeath handler = new DecayOnDeath(new Molecule(Config.TYPE_ENERGY, 100));

        context.reset(environment, 1);
        handler.onDeath(context);

        // Organism 1's cell should be energy
        assertThat(environment.getMolecule(0, 0).type()).isEqualTo(Config.TYPE_ENERGY);

        // Organism 2's cell should be unchanged
        assertThat(environment.getMolecule(9, 9).type()).isEqualTo(Config.TYPE_CODE);
        assertThat(environment.getMolecule(9, 9).value()).isEqualTo(20);
    }

    @Test
    @Tag("unit")
    void isStateless_saveLoadReturnsEmpty() {
        DecayOnDeath handler = new DecayOnDeath(new Molecule(Config.TYPE_ENERGY, 100));
        byte[] state = handler.saveState();
        assertThat(state).isEmpty();

        // loadState should not throw
        handler.loadState(new byte[0]);
    }

    @Test
    @Tag("unit")
    void handlesOrganismWithNoCells() {
        // Organism 99 has no cells
        DecayOnDeath handler = new DecayOnDeath(new Molecule(Config.TYPE_ENERGY, 100));

        context.reset(environment, 99);

        // Should not throw
        handler.onDeath(context);
    }
}
