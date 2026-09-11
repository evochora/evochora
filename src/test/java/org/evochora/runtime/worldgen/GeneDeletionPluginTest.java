package org.evochora.runtime.worldgen;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.MutationRecord;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link GeneDeletionPlugin}.
 */
@Tag("unit")
class GeneDeletionPluginTest {

    private Environment environment;
    private Organism child;

    private static final int LABEL_HASH_A = 11111;
    private static final int LABEL_HASH_B = 22222;

    /** Three hashes the body carries once each, and one it carries twice. */
    private static final int UNIQUE_HASH_1 = 33333;
    private static final int UNIQUE_HASH_2 = 44444;
    private static final int UNIQUE_HASH_3 = 55555;
    private static final int PAIR_HASH = 66666;

    /** The minimum that lets every label of the body be a candidate. */
    private static final int EVERY_LABEL = 1;

    @BeforeEach
    void setUp() {
        environment = new Environment(new int[]{32, 32}, true);

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

        Simulation simulation = new Simulation(environment, policyManager, organismConfig, 1);

        Organism parent = Organism.create(simulation, new int[]{0, 0}, 10000);
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
        GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 1.0, 0.0, EVERY_LABEL); // exponent=0 → uniform
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
        GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 1.0, 2.0, EVERY_LABEL);
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
        GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 1.0, 2.0, EVERY_LABEL);
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
        GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 1.0, 2.0, EVERY_LABEL);
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
        GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 0.0, 2.0, EVERY_LABEL);
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
            GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 1.0, 2.0, EVERY_LABEL);
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
        // Expected ratio: ~27:1. Threshold *10 catches linear-vs-quadratic regression (ratio 9:1 would fail).
        assertThat(hashACount).as("Duplicate labels should be selected much more often")
                .isGreaterThan(hashBCount * 10);
    }

    @Test
    void preservesStructureMolecules() {
        placeLabel(2, 5, LABEL_HASH_A);
        placeCode(3, 5);
        placeStructure(5, 5); // structure in the path

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 1.0, 2.0, EVERY_LABEL);
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
        GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 1.0, 2.0, EVERY_LABEL);
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
        GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 1.0, 2.0, EVERY_LABEL);
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
        GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 1.0, 2.0, EVERY_LABEL);
        plugin.delete(child, environment);

        // Energy molecule should be deleted too
        assertThat(environment.getMolecule(4, 5).isEmpty()).isTrue();
    }

    // ---- Mutation record tests ----

    /** The flat index the environment persists the cell at the given coordinate by. */
    private int flatIndex(int x, int y) {
        return environment.getProperties().toFlatIndex(new int[]{x, y});
    }

    @Test
    void anAppliedDeletionIsRecordedWithTheLabelAndEveryClearedCell() {
        // The only label at x=2, its gene up to the STRUCTURE at x=10
        placeLabel(2, 5, LABEL_HASH_A);
        placeCode(3, 5);
        placeCode(4, 5);
        placeData(5, 5);
        placeStructure(10, 5);

        int labelInt = environment.getMolecule(2, 5).toInt();
        int codeInt = environment.getMolecule(3, 5).toInt();
        int dataInt = environment.getMolecule(5, 5).toInt();

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 1.0, 2.0, EVERY_LABEL);
        plugin.delete(child, environment);

        List<MutationRecord> records = child.getBirthMutations();
        assertThat(records).hasSize(1);
        MutationRecord record = records.get(0);
        assertThat(record.pluginClass()).isEqualTo(GeneDeletionPlugin.class.getName());
        assertThat(record.kind()).isEqualTo("deletion");
        assertThat(record.cells()).containsExactly(
                flatIndex(2, 5), flatIndex(3, 5), flatIndex(4, 5), flatIndex(5, 5));
        assertThat(record.oldValues()).containsExactly(labelInt, codeInt, codeInt, dataInt);
        assertThat(record.newValues()).containsExactly(0, 0, 0, 0);
        assertThat(record.params())
                .as("the chosen label's hash occurs once in the genome")
                .containsExactly(1L);
        assertThat(record.dv()).isEqualTo(child.getDv());
        for (int x = 2; x <= 5; x++) {
            assertThat(environment.getMolecule(x, 5).isEmpty())
                    .as("Cell (%d,5) should be cleared", x).isTrue();
        }
    }

    @Test
    void theRecordCarriesHowOftenTheChosenLabelOccurs() {
        // Three copies of one hash, each alone on its scan line: whichever is chosen, the count is 3
        placeLabel(0, 2, LABEL_HASH_A);
        placeLabel(0, 4, LABEL_HASH_A);
        placeLabel(0, 6, LABEL_HASH_A);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 1.0, 2.0, EVERY_LABEL);
        plugin.delete(child, environment);

        List<MutationRecord> records = child.getBirthMutations();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).params()).containsExactly(3L);
        assertThat(records.get(0).cells()).hasSize(1);
    }

    @Test
    void anOrganismWithoutLabelsRecordsNothing() {
        placeCode(2, 5);
        placeCode(3, 5);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 1.0, 2.0, EVERY_LABEL);
        plugin.delete(child, environment);

        assertThat(child.getBirthMutations()).isNull();
    }

    @Test
    void isStateless() {
        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 0.02, 2.0, EVERY_LABEL);

        byte[] state = plugin.saveState();
        assertThat(state).isEmpty();

        // loadState should not throw
        plugin.loadState(new byte[0]);
    }

    // ---- Candidate rule: a label is drawn from only where the body holds its value often enough ----

    /**
     * Places five blocks, each alone on its scan line so that a deletion on one leaves the others
     * untouched: three labels whose values the body carries once, and two carrying one and the
     * same value.
     */
    private void placeThreeUniqueLabelsAndOnePair() {
        placeLabel(0, 2, UNIQUE_HASH_1);
        placeCode(1, 2);
        placeLabel(0, 4, UNIQUE_HASH_2);
        placeCode(1, 4);
        placeLabel(0, 6, UNIQUE_HASH_3);
        placeCode(1, 6);
        placeLabel(0, 8, PAIR_HASH);
        placeCode(1, 8);
        placeLabel(0, 10, PAIR_HASH);
        placeCode(1, 10);
    }

    /** True where the label of one of the three blocks the body carries once is gone. */
    private boolean aUniqueLabelIsGone() {
        return environment.getMolecule(0, 2).isEmpty()
                || environment.getMolecule(0, 4).isEmpty()
                || environment.getMolecule(0, 6).isEmpty();
    }

    @Test
    void deletionTakesOnlyABlockWhoseLabelTheBodyCarriesTwice() {
        for (int seed = 0; seed < 20; seed++) {
            placeThreeUniqueLabelsAndOnePair();

            IRandomProvider rng = new SeededRandomProvider(seed);
            GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 1.0, 2.0, 2);
            plugin.delete(child, environment);

            assertThat(aUniqueLabelIsGone())
                    .as("seed %d: a label the body carries once is no candidate", seed).isFalse();
            assertThat(environment.getMolecule(0, 8).isEmpty() || environment.getMolecule(0, 10).isEmpty())
                    .as("seed %d: one of the two blocks of the duplicated label is gone", seed).isTrue();
        }

        List<MutationRecord> records = child.getBirthMutations();
        assertThat(records).hasSize(20);
        assertThat(records).allSatisfy(record -> assertThat(record.params())
                .as("the chosen label's value occurs twice")
                .containsExactly(2L));
    }

    @Test
    void aMinimumOfOneLetsTheDeletionTakeAUniqueBlockAgain() {
        boolean uniqueBlockTaken = false;
        for (int seed = 0; seed < 20; seed++) {
            placeThreeUniqueLabelsAndOnePair();

            IRandomProvider rng = new SeededRandomProvider(seed);
            GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 1.0, 2.0, EVERY_LABEL);
            plugin.delete(child, environment);

            uniqueBlockTaken |= aUniqueLabelIsGone();
        }

        assertThat(uniqueBlockTaken)
                .as("with every label a candidate, a block the body carries once is taken too").isTrue();
    }

    @Test
    void aBodyOfUniqueLabelsLosesNothing() {
        placeLabel(0, 2, UNIQUE_HASH_1);
        placeCode(1, 2);
        placeLabel(0, 4, UNIQUE_HASH_2);
        placeCode(1, 4);
        placeLabel(0, 6, UNIQUE_HASH_3);
        placeCode(1, 6);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 1.0, 2.0, 2);
        plugin.delete(child, environment);

        for (int y = 2; y <= 6; y += 2) {
            assertThat(environment.getMolecule(0, y).type()).isEqualTo(Config.TYPE_LABEL);
            assertThat(environment.getMolecule(1, y).type()).isEqualTo(Config.TYPE_CODE);
        }
        assertThat(child.getBirthMutations()).isNull();
    }

    @Test
    void aPairIsNoCandidateWhereThreeCopiesAreAsked() {
        placeLabel(0, 8, PAIR_HASH);
        placeCode(1, 8);
        placeLabel(0, 10, PAIR_HASH);
        placeCode(1, 10);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDeletionPlugin plugin = new GeneDeletionPlugin(rng, 1.0, 2.0, 3);
        plugin.delete(child, environment);

        for (int y = 8; y <= 10; y += 2) {
            assertThat(environment.getMolecule(0, y).type()).isEqualTo(Config.TYPE_LABEL);
            assertThat(environment.getMolecule(1, y).type()).isEqualTo(Config.TYPE_CODE);
        }
        assertThat(child.getBirthMutations()).isNull();
    }

    // ---- Configuration ----

    @Test
    void aConfigurationWithoutTheMinimumIsRejected() {
        IRandomProvider rng = new SeededRandomProvider(42L);
        com.typesafe.config.Config options = ConfigFactory.parseMap(
                Map.of("deletionRate", 0.01, "countExponent", 2.0));

        assertThatThrownBy(() -> new GeneDeletionPlugin(rng, options))
                .hasMessageContaining("minLabelCount");
    }

    @Test
    void aMinimumBelowOneIsRejected() {
        IRandomProvider rng = new SeededRandomProvider(42L);
        com.typesafe.config.Config options = ConfigFactory.parseMap(
                Map.of("deletionRate", 0.01, "countExponent", 2.0, "minLabelCount", 0));

        assertThatThrownBy(() -> new GeneDeletionPlugin(rng, options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minLabelCount");
    }
}
