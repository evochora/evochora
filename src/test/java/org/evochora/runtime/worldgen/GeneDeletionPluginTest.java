package org.evochora.runtime.worldgen;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.MutationRecord;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;
import org.junit.jupiter.api.BeforeAll;
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
    private Simulation simulation;
    private Organism child;

    /** An organism other than the newborn under test. */
    private static final int FOREIGN_ID = 99;

    private static final int LABEL_HASH_A = 11111;
    private static final int LABEL_HASH_B = 22222;

    /** Three hashes the body carries once each, and one it carries twice. */
    private static final int UNIQUE_HASH_1 = 33333;
    private static final int UNIQUE_HASH_2 = 44444;
    private static final int UNIQUE_HASH_3 = 55555;
    private static final int PAIR_HASH = 66666;

    /** The minimum that lets every label of the body be a candidate. */
    private static final int EVERY_LABEL = 1;

    @BeforeAll
    static void initInstructionSet() {
        Instruction.init();
    }

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

        simulation = new Simulation(environment, policyManager, organismConfig, 1);

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
     * Places {@code DUP}, an instruction of one cell after which execution continues, owned by the
     * child.
     */
    private void placeCode(int x, int y) {
        placeOpcode(x, y, "DUP");
    }

    /** Places the opcode cell of the named instruction, owned by the child. */
    private void placeOpcode(int x, int y, String name) {
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName(name)),
                child.getId(), new int[]{x, y});
    }

    /** Places {@code JMPI} with its label reference, two cells, owned by the child. */
    private void placeJump(int x, int y, int hash) {
        placeOpcode(x, y, "JMPI");
        environment.setMolecule(new Molecule(Config.TYPE_LABELREF, hash), child.getId(), new int[]{x + 1, y});
    }

    /** Places {@code IFI %DR0 DATA:0}, a conditional of three cells, owned by the child. */
    private void placeConditional(int x, int y) {
        placeOpcode(x, y, "IFI");
        environment.setMolecule(new Molecule(Config.TYPE_REGISTER, 0), child.getId(), new int[]{x + 1, y});
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 0), child.getId(), new int[]{x + 2, y});
    }

    /**
     * Replaces the newborn under test by one that started at the given position, so that its
     * instruction pointer enters its code there.
     */
    private void newbornStartingAt(int x, int y) {
        child = Organism.restore(3, 9)
                .parentId(child.getParentId())
                .ip(new int[]{x, y})
                .dv(new int[]{1, 0})
                .initialPosition(new int[]{x, y})
                .energy(5000)
                .build(simulation);
        simulation.addOrganism(child);
    }

    /** Whether the cell at the given position is empty. */
    private boolean isEmpty(int x, int y) {
        return environment.getMolecule(x, y).isEmpty();
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

    /** Builds the deletion plugin from its configuration. */
    private static GeneDeletionPlugin deletion(IRandomProvider rng, double deletionRate, double countExponent,
                                               int minLabelCount) {
        return new GeneDeletionPlugin(rng, ConfigFactory.parseMap(Map.of(
                "deletionRate", deletionRate, "countExponent", countExponent, "minLabelCount", minLabelCount)));
    }

    @Test
    void deletesFromLabelToNextLabel() {
        // Label B stands behind A's code, which runs on into it, so only A's block is a candidate;
        // it ends before B, and the DATA cell between its instructions goes with it
        placeLabel(2, 5, LABEL_HASH_A);
        placeCode(3, 5);
        placeCode(4, 5);
        placeData(5, 5);
        placeCode(6, 5);
        placeLabel(8, 5, LABEL_HASH_B);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDeletionPlugin plugin = deletion(rng, 1.0, 0.0, EVERY_LABEL);
        plugin.delete(child, environment);

        for (int x = 2; x <= 6; x++) {
            assertThat(isEmpty(x, 5)).as("Cell (%d,5) should be cleared", x).isTrue();
        }
        assertThat(environment.getMolecule(8, 5).type()).isEqualTo(Config.TYPE_LABEL);
    }

    @Test
    void aShellBehindTheBlockIsLeftStanding() {
        // Layout: [LABEL @ x=2] [CODE @ x=3..5] ... [STRUCTURE @ x=10], the end of the extent
        placeLabel(2, 5, LABEL_HASH_A);
        placeCode(3, 5);
        placeCode(4, 5);
        placeCode(5, 5);
        placeStructure(10, 5);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDeletionPlugin plugin = deletion(rng, 1.0, 2.0, EVERY_LABEL);
        plugin.delete(child, environment);

        for (int x = 2; x <= 5; x++) {
            assertThat(isEmpty(x, 5)).as("Cell (%d,5) should be cleared", x).isTrue();
        }
        assertThat(environment.getMolecule(10, 5).type()).isEqualTo(Config.TYPE_STRUCTURE);
    }

    @Test
    void passesOverAForeignMolecule() {
        placeLabel(2, 5, LABEL_HASH_A);
        placeCode(3, 5);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 42), FOREIGN_ID, new int[]{5, 5});
        placeCode(6, 5);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDeletionPlugin plugin = deletion(rng, 1.0, 2.0, EVERY_LABEL);
        plugin.delete(child, environment);

        assertThat(isEmpty(2, 5)).isTrue();
        assertThat(isEmpty(3, 5)).isTrue();
        assertThat(isEmpty(6, 5)).as("the child's code behind the foreign cell is part of the block").isTrue();
        assertThat(environment.getOwnerId(5, 5)).isEqualTo(FOREIGN_ID);
        assertThat(environment.getMolecule(5, 5).value()).isEqualTo(42);
    }

    @Test
    void skipsOrganismWithNoLabels() {
        // Only CODE molecules, no labels
        placeCode(2, 5);
        placeCode(3, 5);
        placeCode(4, 5);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDeletionPlugin plugin = deletion(rng, 1.0, 2.0, EVERY_LABEL);
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
        GeneDeletionPlugin plugin = deletion(rng, 0.0, 2.0, EVERY_LABEL);
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
            GeneDeletionPlugin plugin = deletion(rng, 1.0, 2.0, EVERY_LABEL);
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
        GeneDeletionPlugin plugin = deletion(rng, 1.0, 2.0, EVERY_LABEL);
        plugin.delete(child, environment);

        // Structure should be preserved (deletion stops before it)
        assertThat(environment.getMolecule(5, 5).type()).isEqualTo(Config.TYPE_STRUCTURE);
    }

    @Test
    void deletedCellsAreUnowned() {
        placeLabel(2, 5, LABEL_HASH_A);
        placeCode(3, 5);
        placeData(4, 5);
        placeCode(5, 5);
        placeStructure(8, 5);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDeletionPlugin plugin = deletion(rng, 1.0, 2.0, EVERY_LABEL);
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
        GeneDeletionPlugin plugin = deletion(rng, 1.0, 2.0, EVERY_LABEL);
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
        GeneDeletionPlugin plugin = deletion(rng, 1.0, 2.0, EVERY_LABEL);
        plugin.delete(child, environment);

        // Energy molecule should be deleted too
        assertThat(environment.getMolecule(4, 5).isEmpty()).isTrue();
    }

    // ---- Block boundaries: no cliff, nothing outside the genome ----

    @Test
    void aBlockEndsBehindItsJumpAndLeavesADataWallStanding() {
        placeLabel(2, 5, LABEL_HASH_A);
        placeCode(3, 5);
        placeJump(4, 5, LABEL_HASH_B);
        placeData(9, 5);

        IRandomProvider rng = new SeededRandomProvider(42L);
        deletion(rng, 1.0, 2.0, EVERY_LABEL).delete(child, environment);

        for (int x = 2; x <= 5; x++) {
            assertThat(isEmpty(x, 5)).as("Cell (%d,5) should be cleared", x).isTrue();
        }
        assertThat(environment.getMolecule(9, 5).type()).isEqualTo(Config.TYPE_DATA);
    }

    @Test
    void aBlockEndsBehindItsReturnAndLeavesAStructureWallStanding() {
        placeLabel(2, 5, LABEL_HASH_A);
        placeCode(3, 5);
        placeOpcode(4, 5, "RET");
        placeStructure(9, 5);

        IRandomProvider rng = new SeededRandomProvider(42L);
        deletion(rng, 1.0, 2.0, EVERY_LABEL).delete(child, environment);

        for (int x = 2; x <= 4; x++) {
            assertThat(isEmpty(x, 5)).as("Cell (%d,5) should be cleared", x).isTrue();
        }
        assertThat(environment.getMolecule(9, 5).type()).isEqualTo(Config.TYPE_STRUCTURE);
    }

    @Test
    void aLabelExecutionRunsIntoIsNoCandidate() {
        // Line 5: code entered at a label of its own runs on into the pair's label; line 7: the
        // pair's label opens its line
        placeLabel(1, 5, UNIQUE_HASH_1);
        placeCode(2, 5);
        placeLabel(3, 5, PAIR_HASH);
        placeCode(4, 5);
        placeLabel(0, 7, PAIR_HASH);
        placeCode(1, 7);

        for (int seed = 0; seed < 20; seed++) {
            placeLabel(0, 7, PAIR_HASH);
            placeCode(1, 7);
            IRandomProvider rng = new SeededRandomProvider(seed);
            deletion(rng, 1.0, 2.0, 2).delete(child, environment);

            assertThat(environment.getMolecule(3, 5).type())
                    .as("seed %d: the label code runs into stays", seed).isEqualTo(Config.TYPE_LABEL);
            assertThat(isEmpty(0, 7)).as("seed %d: the other label is the one deleted", seed).isTrue();
        }
    }

    @Test
    void aLabelBehindAJumpIsACandidate() {
        boolean labelBehindTheJumpTaken = false;
        for (int seed = 0; seed < 20 && !labelBehindTheJumpTaken; seed++) {
            placeLabel(2, 5, LABEL_HASH_A);
            placeJump(3, 5, LABEL_HASH_B);
            placeLabel(6, 5, LABEL_HASH_B);
            placeCode(7, 5);

            IRandomProvider rng = new SeededRandomProvider(seed);
            deletion(rng, 1.0, 0.0, EVERY_LABEL).delete(child, environment);

            labelBehindTheJumpTaken = isEmpty(6, 5);
        }

        assertThat(labelBehindTheJumpTaken).isTrue();
    }

    @Test
    void aConditionalBeforeTheJumpKeepsTheLabelBehindItOut() {
        for (int seed = 0; seed < 20; seed++) {
            placeLabel(2, 5, LABEL_HASH_A);
            placeConditional(3, 5);
            placeJump(6, 5, LABEL_HASH_A);
            placeLabel(9, 5, LABEL_HASH_B);
            placeCode(10, 5);

            IRandomProvider rng = new SeededRandomProvider(seed);
            deletion(rng, 1.0, 0.0, EVERY_LABEL).delete(child, environment);

            assertThat(environment.getMolecule(9, 5).type())
                    .as("seed %d: the conditional can skip the jump into the label", seed)
                    .isEqualTo(Config.TYPE_LABEL);
        }
    }

    @Test
    void aLabelTheInitialPositionRunsIntoIsNoCandidate() {
        newbornStartingAt(2, 5);
        placeStructure(1, 5);
        placeLabel(4, 5, LABEL_HASH_A);
        placeCode(5, 5);

        IRandomProvider rng = new SeededRandomProvider(42L);
        deletion(rng, 1.0, 2.0, EVERY_LABEL).delete(child, environment);

        assertThat(environment.getMolecule(4, 5).type()).isEqualTo(Config.TYPE_LABEL);
        assertThat(child.getBirthMutations()).isNull();
    }

    @Test
    void aBlockTheNewbornStartsInIsNoCandidate() {
        newbornStartingAt(5, 5);
        placeLabel(2, 5, LABEL_HASH_A);
        placeCode(3, 5);
        placeCode(4, 5);
        placeCode(6, 5);

        IRandomProvider rng = new SeededRandomProvider(42L);
        deletion(rng, 1.0, 2.0, EVERY_LABEL).delete(child, environment);

        assertThat(environment.getMolecule(2, 5).type()).isEqualTo(Config.TYPE_LABEL);
        assertThat(child.getBirthMutations()).isNull();
    }

    @Test
    void aLabelInAnOperandSlotIsNeitherCandidateNorCounted() {
        // Line 5: the pair's value stands as the jump's operand; line 7: as a label opening its line
        placeLabel(2, 5, LABEL_HASH_A);
        placeOpcode(3, 5, "JMPI");
        placeLabel(4, 5, PAIR_HASH);
        placeLabel(0, 7, PAIR_HASH);
        placeCode(1, 7);

        IRandomProvider rng = new SeededRandomProvider(42L);
        deletion(rng, 1.0, 2.0, 2).delete(child, environment);

        assertThat(environment.getMolecule(4, 5).type()).isEqualTo(Config.TYPE_LABEL);
        assertThat(environment.getMolecule(0, 7).type()).isEqualTo(Config.TYPE_LABEL);
        assertThat(child.getBirthMutations()).isNull();
    }

    @Test
    void unownedAndForeignCellsInTheBlockAreLeftStanding() {
        placeLabel(2, 5, LABEL_HASH_A);
        placeCode(3, 5);
        environment.setMolecule(new Molecule(Config.TYPE_ENERGY, 500), 0, new int[]{4, 5});
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 7), FOREIGN_ID, new int[]{5, 5});
        placeCode(6, 5);

        IRandomProvider rng = new SeededRandomProvider(42L);
        deletion(rng, 1.0, 2.0, EVERY_LABEL).delete(child, environment);

        assertThat(isEmpty(2, 5)).isTrue();
        assertThat(isEmpty(3, 5)).isTrue();
        assertThat(isEmpty(6, 5)).isTrue();
        assertThat(environment.getMolecule(4, 5).type()).isEqualTo(Config.TYPE_ENERGY);
        assertThat(environment.getOwnerId(4, 5)).isEqualTo(0);
        assertThat(environment.getMolecule(5, 5).type()).isEqualTo(Config.TYPE_DATA);
        assertThat(environment.getOwnerId(5, 5)).isEqualTo(FOREIGN_ID);
    }

    @Test
    void unownedEnergyBeyondTheBodyIsNeverTouched() {
        // A block without a closing jump, a wall of DATA, and free energy behind the wall
        placeLabel(2, 5, LABEL_HASH_A);
        placeCode(3, 5);
        placeData(6, 5);
        for (int x = 7; x < 20; x++) {
            environment.setMolecule(new Molecule(Config.TYPE_ENERGY, 5000), 0, new int[]{x, 5});
        }

        IRandomProvider rng = new SeededRandomProvider(42L);
        deletion(rng, 1.0, 2.0, EVERY_LABEL).delete(child, environment);

        assertThat(isEmpty(2, 5)).isTrue();
        assertThat(isEmpty(3, 5)).isTrue();
        assertThat(environment.getMolecule(6, 5).type()).isEqualTo(Config.TYPE_DATA);
        for (int x = 7; x < 20; x++) {
            assertThat(environment.getMolecule(x, 5).type())
                    .as("Cell (%d,5) should keep its energy", x).isEqualTo(Config.TYPE_ENERGY);
        }
    }

    @Test
    void aForeignLabelEndsTheBlock() {
        placeLabel(2, 5, LABEL_HASH_A);
        placeCode(3, 5);
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, LABEL_HASH_B), FOREIGN_ID, new int[]{4, 5});
        placeCode(5, 5);

        IRandomProvider rng = new SeededRandomProvider(42L);
        deletion(rng, 1.0, 2.0, EVERY_LABEL).delete(child, environment);

        assertThat(isEmpty(2, 5)).isTrue();
        assertThat(isEmpty(3, 5)).isTrue();
        assertThat(environment.getMolecule(4, 5).type()).isEqualTo(Config.TYPE_LABEL);
        assertThat(environment.getMolecule(5, 5).type()).isEqualTo(Config.TYPE_CODE);
    }

    // ---- Mutation record tests ----

    /** The flat index the environment persists the cell at the given coordinate by. */
    private int flatIndex(int x, int y) {
        return environment.getProperties().toFlatIndex(new int[]{x, y});
    }

    @Test
    void anAppliedDeletionIsRecordedWithTheLabelAndEveryClearedCell() {
        // The only label at x=2, its gene up to the last instruction before the STRUCTURE at x=10
        placeLabel(2, 5, LABEL_HASH_A);
        placeCode(3, 5);
        placeData(4, 5);
        placeCode(5, 5);
        placeStructure(10, 5);

        int labelInt = environment.getMolecule(2, 5).toInt();
        int codeInt = environment.getMolecule(3, 5).toInt();
        int dataInt = environment.getMolecule(4, 5).toInt();

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDeletionPlugin plugin = deletion(rng, 1.0, 2.0, EVERY_LABEL);
        plugin.delete(child, environment);

        List<MutationRecord> records = child.getBirthMutations();
        assertThat(records).hasSize(1);
        MutationRecord record = records.get(0);
        assertThat(record.pluginClass()).isEqualTo(GeneDeletionPlugin.class.getName());
        assertThat(record.kind()).isEqualTo("deletion");
        assertThat(record.cells()).containsExactly(
                flatIndex(2, 5), flatIndex(3, 5), flatIndex(4, 5), flatIndex(5, 5));
        assertThat(record.oldValues()).containsExactly(labelInt, codeInt, dataInt, codeInt);
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
        GeneDeletionPlugin plugin = deletion(rng, 1.0, 2.0, EVERY_LABEL);
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
        GeneDeletionPlugin plugin = deletion(rng, 1.0, 2.0, EVERY_LABEL);
        plugin.delete(child, environment);

        assertThat(child.getBirthMutations()).isNull();
    }

    @Test
    void isStateless() {
        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDeletionPlugin plugin = deletion(rng, 0.02, 2.0, EVERY_LABEL);

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
            GeneDeletionPlugin plugin = deletion(rng, 1.0, 2.0, 2);
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
            GeneDeletionPlugin plugin = deletion(rng, 1.0, 2.0, EVERY_LABEL);
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
        GeneDeletionPlugin plugin = deletion(rng, 1.0, 2.0, 2);
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
        GeneDeletionPlugin plugin = deletion(rng, 1.0, 2.0, 3);
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
