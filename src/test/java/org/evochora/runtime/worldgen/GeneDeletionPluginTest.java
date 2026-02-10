package org.evochora.runtime.worldgen;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GeneDeletionPlugin}.
 */
@Tag("unit")
class GeneDeletionPluginTest {

    private Environment environment;
    private Organism child;

    private static final int LABEL_HASH_A = 11111;
    private static final int LABEL_HASH_B = 22222;

    @BeforeEach
    void setUp() {
        environment = new Environment(new int[]{30, 20}, true);

        String thermoConfigStr = """
            default {
              className = "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy"
              options {
                base-energy = 1
                base-entropy = 1
              }
            }
            overrides {
              instructions {}
              families {}
            }
            """;
        ThermodynamicPolicyManager policyManager = new ThermodynamicPolicyManager(
                ConfigFactory.parseString(thermoConfigStr));

        com.typesafe.config.Config organismConfig = ConfigFactory.parseMap(Map.of(
                "max-energy", 32767,
                "max-entropy", 8191,
                "error-penalty-cost", 10
        ));

        Simulation simulation = new Simulation(environment, policyManager, organismConfig);

        Organism parent = Organism.create(simulation, new int[]{0, 0}, 10000, null);
        simulation.addOrganism(parent);

        child = Organism.restore(2, 9)
                .parentId(parent.getId())
                .ip(new int[]{0, 0})
                .dv(new int[]{1, 0})
                .initialPosition(new int[]{0, 0})
                .energy(5000)
                .build(simulation);
        simulation.addOrganism(child);
    }

    /**
     * Places a LABEL molecule at the given position, owned by the child.
     */
    private void placeLabel(int x, int y, int hash) {
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, hash), child.getId(), new int[]{x, y});
    }

    /**
     * Places a CODE molecule (non-NOP) at the given position, owned by the child.
     */
    private void placeCode(int x, int y) {
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 42), child.getId(), new int[]{x, y});
    }

    /**
     * Places a DATA molecule at the given position, owned by the child.
     */
    private void placeData(int x, int y) {
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 100), child.getId(), new int[]{x, y});
    }

    /**
     * Places a STRUCTURE molecule at the given position, owned by the child.
     */
    private void placeStructure(int x, int y) {
        environment.setMolecule(new Molecule(Config.TYPE_STRUCTURE, 100), child.getId(), new int[]{x, y});
    }

    @Test
    void deletesFromLabelToNextLabel() {
        // Layout: [LABEL_A @ x=2] [CODE @ x=3] [CODE @ x=4] [DATA @ x=5] [LABEL_B @ x=8]
        // NOP gaps at x=6,7 (unowned)
        placeLabel(2, 5, LABEL_HASH_A);
        placeCode(3, 5);
        placeCode(4, 5);
        placeData(5, 5);
        placeLabel(8, 5, LABEL_HASH_B);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 1.0, 0.0); // exponent=0 → uniform
        plugin.delete(child, environment);

        // One of the labels was selected and its block deleted
        boolean labelADeleted = environment.getMolecule(2, 5).isEmpty();
        boolean labelBDeleted = environment.getMolecule(8, 5).isEmpty();

        // Exactly one label block should be deleted
        assertThat(labelADeleted || labelBDeleted).isTrue();

        if (labelADeleted) {
            // Label A and code between A and B should be deleted
            assertThat(environment.getMolecule(2, 5).isEmpty()).isTrue();
            assertThat(environment.getMolecule(3, 5).isEmpty()).isTrue();
            assertThat(environment.getMolecule(4, 5).isEmpty()).isTrue();
            assertThat(environment.getMolecule(5, 5).isEmpty()).isTrue();
            // Label B should still exist
            assertThat(environment.getMolecule(8, 5).type()).isEqualTo(Config.TYPE_LABEL);
        } else {
            // Label B deleted, label A should still exist
            assertThat(environment.getMolecule(2, 5).type()).isEqualTo(Config.TYPE_LABEL);
            assertThat(environment.getMolecule(8, 5).isEmpty()).isTrue();
        }
    }

    @Test
    void deletesFromLabelToStructure() {
        // Layout: [LABEL @ x=2] [CODE @ x=3] [CODE @ x=4] ... [STRUCTURE @ x=10]
        placeLabel(2, 5, LABEL_HASH_A);
        placeCode(3, 5);
        placeCode(4, 5);
        placeCode(5, 5);
        placeStructure(10, 5);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 1.0, 2.0);
        plugin.delete(child, environment);

        // Label and all code should be deleted
        assertThat(environment.getMolecule(2, 5).isEmpty()).isTrue();
        assertThat(environment.getMolecule(3, 5).isEmpty()).isTrue();
        assertThat(environment.getMolecule(4, 5).isEmpty()).isTrue();
        assertThat(environment.getMolecule(5, 5).isEmpty()).isTrue();
        // Structure should remain
        assertThat(environment.getMolecule(10, 5).type()).isEqualTo(Config.TYPE_STRUCTURE);
    }

    @Test
    void stopsAtForeignMolecule() {
        // Place label and code owned by child, then a foreign molecule
        placeLabel(2, 5, LABEL_HASH_A);
        placeCode(3, 5);
        // Foreign molecule at x=5 (owned by organism 99)
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 42), 99, new int[]{5, 5});

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 1.0, 2.0);
        plugin.delete(child, environment);

        // Label and code should be deleted
        assertThat(environment.getMolecule(2, 5).isEmpty()).isTrue();
        assertThat(environment.getMolecule(3, 5).isEmpty()).isTrue();
        // Foreign molecule should be untouched
        assertThat(environment.getOwnerId(5, 5)).isEqualTo(99);
    }

    @Test
    void skipsOrganismWithNoLabels() {
        // Only CODE molecules, no labels
        placeCode(2, 5);
        placeCode(3, 5);
        placeCode(4, 5);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 1.0, 2.0);
        plugin.delete(child, environment);

        // Code should remain untouched
        assertThat(environment.getMolecule(2, 5).type()).isEqualTo(Config.TYPE_CODE);
        assertThat(environment.getMolecule(3, 5).type()).isEqualTo(Config.TYPE_CODE);
        assertThat(environment.getMolecule(4, 5).type()).isEqualTo(Config.TYPE_CODE);
    }

    @Test
    void zeroDeletionRateNeverDeletes() {
        placeLabel(2, 5, LABEL_HASH_A);
        placeCode(3, 5);
        placeCode(4, 5);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 0.0, 2.0);
        plugin.onBirth(child, environment);

        // Everything should remain
        assertThat(environment.getMolecule(2, 5).type()).isEqualTo(Config.TYPE_LABEL);
        assertThat(environment.getMolecule(3, 5).type()).isEqualTo(Config.TYPE_CODE);
    }

    @Test
    void duplicateLabelsSelectedMoreOften() {
        // Place 3 labels with HASH_A (duplicates) and 1 with HASH_B (unique)
        placeLabel(0, 2, LABEL_HASH_A);
        placeLabel(0, 4, LABEL_HASH_A);
        placeLabel(0, 6, LABEL_HASH_A);
        placeLabel(0, 8, LABEL_HASH_B);

        // Run deletion many times with different seeds, count which hash gets deleted
        int hashACount = 0;
        int hashBCount = 0;
        for (int seed = 0; seed < 200; seed++) {
            // Reset environment for each trial
            environment.setMolecule(new Molecule(Config.TYPE_LABEL, LABEL_HASH_A), child.getId(), new int[]{0, 2});
            environment.setMolecule(new Molecule(Config.TYPE_LABEL, LABEL_HASH_A), child.getId(), new int[]{0, 4});
            environment.setMolecule(new Molecule(Config.TYPE_LABEL, LABEL_HASH_A), child.getId(), new int[]{0, 6});
            environment.setMolecule(new Molecule(Config.TYPE_LABEL, LABEL_HASH_B), child.getId(), new int[]{0, 8});

            IRandomProvider rng = new SeededRandomProvider(seed);
            GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 1.0, 2.0);
            plugin.delete(child, environment);

            // Check which label was deleted
            if (environment.getMolecule(0, 2).isEmpty()
                    || environment.getMolecule(0, 4).isEmpty()
                    || environment.getMolecule(0, 6).isEmpty()) {
                hashACount++;
            }
            if (environment.getMolecule(0, 8).isEmpty()) {
                hashBCount++;
            }
        }

        // With exponent=2: hash_A weight per label = 3^2=9, total=27; hash_B weight = 1^2=1, total=1
        // Expected ratio: ~27:1. Hash A should be selected much more often.
        assertThat(hashACount).as("Duplicate labels should be selected much more often")
                .isGreaterThan(hashBCount * 5);
    }

    @Test
    void preservesStructureMolecules() {
        placeLabel(2, 5, LABEL_HASH_A);
        placeCode(3, 5);
        placeStructure(5, 5); // structure in the path

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 1.0, 2.0);
        plugin.delete(child, environment);

        // Structure should be preserved (deletion stops before it)
        assertThat(environment.getMolecule(5, 5).type()).isEqualTo(Config.TYPE_STRUCTURE);
    }

    @Test
    void deletedCellsAreUnowned() {
        placeLabel(2, 5, LABEL_HASH_A);
        placeCode(3, 5);
        placeCode(4, 5);
        placeData(5, 5);
        placeStructure(8, 5);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 1.0, 2.0);
        plugin.delete(child, environment);

        // All deleted cells should have owner=0
        for (int x = 2; x <= 5; x++) {
            assertThat(environment.getOwnerId(x, 5)).as("Cell (%d,5) should be unowned", x).isEqualTo(0);
        }
    }

    @Test
    void bridgesNopGaps() {
        // Layout with NOP gaps between owned molecules:
        // [LABEL @ x=2] ... [CODE @ x=5] ... [CODE @ x=8] [STRUCTURE @ x=12]
        placeLabel(2, 5, LABEL_HASH_A);
        // x=3,4 are NOP (unowned)
        placeCode(5, 5);
        // x=6,7 are NOP (unowned)
        placeCode(8, 5);
        placeStructure(12, 5);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 1.0, 2.0);
        plugin.delete(child, environment);

        // All owned molecules between label and structure should be deleted
        assertThat(environment.getMolecule(2, 5).isEmpty()).isTrue();
        assertThat(environment.getMolecule(5, 5).isEmpty()).isTrue();
        assertThat(environment.getMolecule(8, 5).isEmpty()).isTrue();
        // Structure still stands
        assertThat(environment.getMolecule(12, 5).type()).isEqualTo(Config.TYPE_STRUCTURE);
    }

    @Test
    void deletesEnergyMolecules() {
        placeLabel(2, 5, LABEL_HASH_A);
        // Energy molecule in the deletion path
        environment.setMolecule(new Molecule(Config.TYPE_ENERGY, 500), child.getId(), new int[]{4, 5});
        placeCode(5, 5);
        placeStructure(8, 5);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 1.0, 2.0);
        plugin.delete(child, environment);

        // Energy molecule should be deleted too
        assertThat(environment.getMolecule(4, 5).isEmpty()).isTrue();
    }

    @Test
    void isStateless() {
        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 0.02, 2.0);

        byte[] state = plugin.saveState();
        assertThat(state).isEmpty();

        // loadState should not throw
        plugin.loadState(new byte[0]);
    }
}
