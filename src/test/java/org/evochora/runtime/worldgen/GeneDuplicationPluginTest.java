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

/**
 * Unit tests for {@link GeneDuplicationPlugin}.
 */
@Tag("unit")
class GeneDuplicationPluginTest {

    private Simulation simulation;
    private Environment environment;

    /** The child organism that was just born. */
    private Organism child;

    /** ADDR opcode: two register operands, so an ADDR instruction occupies three cells. */
    private static int ADDR_OPCODE;

    /** SETV opcode: a register and a vector operand, so it occupies four cells in a 2D world. */
    private static int SETV_OPCODE;

    @BeforeAll
    static void initInstructions() {
        Instruction.init();
        ADDR_OPCODE = Instruction.getInstructionIdByName("ADDR");
        SETV_OPCODE = Instruction.getInstructionIdByName("SETV");
    }

    @BeforeEach
    void setUp() {
        // 32x32 toroidal environment
        environment = new Environment(new int[]{32, 32}, true);

        // Minimal thermodynamic config
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

        // Create parent organism (born at tick 0, no parent)
        Organism parent = Organism.create(simulation, new int[]{0, 0}, 10000);
        simulation.addOrganism(parent);

        // Create child organism
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
     * Places a row of molecules owned by the child at the given y coordinate.
     * Simulates a code row with a LABEL at position (labelX, y) and code elsewhere.
     */
    private void placeCodeRow(int y, int fromX, int toX, int labelX) {
        for (int x = fromX; x <= toX; x++) {
            Molecule mol;
            if (x == labelX) {
                mol = new Molecule(Config.TYPE_LABEL, 12345);
            } else {
                mol = new Molecule(Config.TYPE_CODE, 42); // some instruction
            }
            environment.setMolecule(mol, child.getId(), new int[]{x, y});
        }
    }

    /**
     * Places an empty row owned by the child (CODE:0 cells with ownership).
     * This simulates an empty code row between structure edges.
     */
    private void placeEmptyOwnedRow(int y, int fromX, int toX) {
        for (int x = fromX; x <= toX; x++) {
            environment.setMolecule(new Molecule(Config.TYPE_CODE, 0), child.getId(), new int[]{x, y});
        }
    }

    @Test
    void duplicatesCodeBlockIntoNopArea() {
        // Code row at y=2: NOP area from x=0..6, code from x=7..14 with a label at x=12, so that
        // the block from that label is short enough for either NOP area
        placeEmptyOwnedRow(2, 0, 6); // NOP area on same row as code
        placeCodeRow(2, 7, 14, 12);

        // Second scan line: entirely empty (another NOP target option)
        placeEmptyOwnedRow(4, 0, 14);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 1.0, 3);
        plugin.onBirth(child, environment);

        // Verify that some non-empty molecules were copied to an NOP area
        // (either y=2 x=0..6 or y=4 x=0..14)
        int copiedCount = 0;
        for (int y : new int[]{2, 4}) {
            int endX = (y == 2) ? 6 : 14;
            for (int x = 0; x <= endX; x++) {
                Molecule mol = environment.getMolecule(x, y);
                if (!mol.isEmpty()) {
                    // Verify owner is the child
                    assertThat(environment.getOwnerId(x, y)).isEqualTo(child.getId());
                    copiedCount++;
                }
            }
        }
        assertThat(copiedCount).as("At least some molecules should be copied into a NOP area").isGreaterThan(0);
    }

    @Test
    void skipsOrganismWithNoLabels() {
        // Code row WITHOUT any labels
        for (int x = 0; x <= 14; x++) {
            environment.setMolecule(new Molecule(Config.TYPE_CODE, 42), child.getId(), new int[]{x, 2});
        }
        placeEmptyOwnedRow(4, 0, 14);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 1.0, 3);
        plugin.onBirth(child, environment);

        // Empty row should remain empty
        for (int x = 0; x <= 14; x++) {
            assertThat(environment.getMolecule(x, 4).value()).isEqualTo(0);
        }
    }

    @Test
    void skipsWhenNopAreaTooSmall() {
        // Code row with label
        placeCodeRow(2, 0, 14, 5);

        // Row with only 2 empty cells (less than minNopSize=5)
        for (int x = 0; x <= 14; x++) {
            if (x >= 7 && x <= 8) {
                environment.setMolecule(new Molecule(Config.TYPE_CODE, 0), child.getId(), new int[]{x, 4});
            } else {
                environment.setMolecule(new Molecule(Config.TYPE_CODE, 42), child.getId(), new int[]{x, 4});
            }
        }

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 1.0, 5); // minNopSize=5
        plugin.onBirth(child, environment);

        // The two empty cells should still be empty
        assertThat(environment.getMolecule(7, 4).isEmpty()).isTrue();
        assertThat(environment.getMolecule(8, 4).isEmpty()).isTrue();
    }

    @Test
    void zeroDuplicationRateNeverDuplicates() {
        placeCodeRow(2, 0, 14, 5);
        placeEmptyOwnedRow(4, 0, 14);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 0.0, 3); // rate=0
        plugin.onBirth(child, environment);

        // Empty row should remain empty
        for (int x = 0; x <= 14; x++) {
            assertThat(environment.getMolecule(x, 4).value()).isEqualTo(0);
        }
    }

    @Test
    void copiedMoleculesHaveCorrectOwner() {
        // Set up a scenario where duplication will definitely happen
        // One code row with label at x=0, one completely empty row
        placeCodeRow(2, 0, 14, 0);
        placeEmptyOwnedRow(4, 0, 14);

        IRandomProvider rng = new SeededRandomProvider(123L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 1.0, 3);
        plugin.onBirth(child, environment);

        // All non-empty cells should have child as owner
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 30; x++) {
                if (!environment.getMolecule(x, y).isEmpty()) {
                    int owner = environment.getOwnerId(x, y);
                    if (owner != 0) {
                        assertThat(owner).isEqualTo(child.getId());
                    }
                }
            }
        }
    }

    @Test
    void copyLengthLimitedBySourceEdge() {
        // Short code row: label at x=10, code ends at x=12 (only 3 molecules from label)
        for (int x = 10; x <= 12; x++) {
            Molecule mol = (x == 10)
                    ? new Molecule(Config.TYPE_LABEL, 99999)
                    : new Molecule(Config.TYPE_CODE, 42);
            environment.setMolecule(mol, child.getId(), new int[]{x, 2});
        }

        // Large empty row (20 cells) - much bigger than source
        placeEmptyOwnedRow(4, 0, 19);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 1.0, 2);
        plugin.onBirth(child, environment);

        // Count non-empty cells copied to y=4
        int copiedCount = 0;
        for (int x = 0; x <= 19; x++) {
            if (!environment.getMolecule(x, 4).isEmpty()) {
                copiedCount++;
            }
        }
        // Should copy at most 3 molecules (label + 2 code cells)
        assertThat(copiedCount).isLessThanOrEqualTo(3);
    }

    @Test
    void duplicatesIntoNopGapAtTheWorldEdgeOfASmallWrappingBody() {
        // y=2: source gene, LABEL at x=28, CODE at x=27,29,30,31,0,1.
        // y=4: owned cells at x=27,28,29 and x=4,5 only; the unowned empty run x=30,31,0,1,2,3 is
        // the interior NOP gap, lying exactly on the world edge and holding the whole block. The raw
        // span 4..29 is shorter than the axis, yet the shortest arc wraps. External space: x=6..26.
        int id = child.getId();
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, 12345), id, new int[]{28, 2});
        for (int x : new int[]{27, 29, 30, 31, 0, 1}) {
            environment.setMolecule(new Molecule(Config.TYPE_CODE, 42), id, new int[]{x, 2});
        }
        for (int x : new int[]{27, 28, 29, 4, 5}) {
            environment.setMolecule(new Molecule(Config.TYPE_CODE, 42), id, new int[]{x, 4});
        }

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 1.0, 3);
        plugin.onBirth(child, environment);

        for (int x = 6; x <= 26; x++) {
            assertThat(environment.getMolecule(x, 4).isEmpty())
                    .as("External cell (%d,4) should remain empty", x).isTrue();
        }
        int copiedCount = 0;
        for (int x : new int[]{30, 31, 0, 1, 2, 3}) {
            if (!environment.getMolecule(x, 4).isEmpty()) {
                copiedCount++;
            }
        }
        assertThat(copiedCount).as("Should copy molecules into the NOP gap at the world edge").isGreaterThan(0);
    }

    @Test
    void duplicatesIntoInteriorNopAreaWhenWrapping() {
        // Organism wraps around the x=0/31 boundary of the 32-wide world.
        // y=2: LABEL at x=30, CODE at x=29,31,0,1,2 (source gene wrapping through boundary).
        // y=4: Empty owned cells at x=29..31,0..3 (NOP target within interior).
        // External space on y=4: x=4..28 (must remain empty).
        int id = child.getId();
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, 12345), id, new int[]{30, 2});
        for (int x : new int[]{29, 31, 0, 1, 2}) {
            environment.setMolecule(new Molecule(Config.TYPE_CODE, 42), id, new int[]{x, 2});
        }

        // Empty owned NOP area wrapping through boundary
        for (int x : new int[]{29, 30, 31, 0, 1, 2, 3}) {
            environment.setMolecule(new Molecule(Config.TYPE_CODE, 0), id, new int[]{x, 4});
        }

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 1.0, 3);
        plugin.onBirth(child, environment);

        // External space on y=4 must remain empty
        for (int x = 4; x <= 28; x++) {
            assertThat(environment.getMolecule(x, 4).isEmpty())
                    .as("External cell (%d,4) should remain empty", x).isTrue();
        }

        // Some molecules should be copied into the interior NOP area
        int copiedCount = 0;
        for (int x : new int[]{29, 30, 31, 0, 1, 2, 3}) {
            if (!environment.getMolecule(x, 4).isEmpty()) {
                copiedCount++;
            }
        }
        assertThat(copiedCount).as("Should copy molecules into interior NOP area").isGreaterThan(0);
    }

    @Test
    void negativeDvCopiesWithinNopArea() {
        // Child with DV=(-1, 0).
        // y=2: LABEL at x=10, CODE at x=7,8,9,11,12. Source extends from label leftward (4 cells).
        // y=4: Empty owned cells from x=5 to x=15 (NOP target).
        // Without the negative-DV fix, the copy would overwrite cells to the left of the NOP area.
        Organism negChild = Organism.restore(3, 9)
                .parentId(1)
                .ip(new int[]{0, 0})
                .dv(new int[]{-1, 0})
                .initialPosition(new int[]{0, 0})
                .energy(5000)
                .build(simulation);
        simulation.addOrganism(negChild);

        int negId = negChild.getId();
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, 12345), negId, new int[]{10, 2});
        for (int x : new int[]{7, 8, 9, 11, 12}) {
            environment.setMolecule(new Molecule(Config.TYPE_CODE, 42), negId, new int[]{x, 2});
        }

        // Empty owned NOP area at y=4
        for (int x = 5; x <= 15; x++) {
            environment.setMolecule(new Molecule(Config.TYPE_CODE, 0), negId, new int[]{x, 4});
        }

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 1.0, 3);
        plugin.onBirth(negChild, environment);

        // External space on y=4 must remain empty
        for (int x = 0; x < 5; x++) {
            assertThat(environment.getMolecule(x, 4).isEmpty())
                    .as("Cell (%d,4) outside NOP area should be empty", x).isTrue();
        }
        for (int x = 16; x < 30; x++) {
            assertThat(environment.getMolecule(x, 4).isEmpty())
                    .as("Cell (%d,4) outside NOP area should be empty", x).isTrue();
        }

        // Some molecules should be copied into the NOP area
        int copiedCount = 0;
        for (int x = 5; x <= 15; x++) {
            if (!environment.getMolecule(x, 4).isEmpty()) {
                copiedCount++;
            }
        }
        assertThat(copiedCount).as("Should copy molecules into NOP area").isGreaterThan(0);
    }

    // ---- Mutation record tests ----

    /** The flat index the environment persists the cell at the given coordinate by. */
    private int flatIndex(int x, int y) {
        return environment.getProperties().toFlatIndex(new int[]{x, y});
    }

    @Test
    void anAppliedCopyIsRecordedWithItsTargetCellsAndItsSource() {
        // One gene at y=2 (LABEL at x=10, CODE at x=11 and x=12) and one empty row at y=4 as the
        // only scan line with a NOP run: three molecules land at x=0..2 of that row
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, 12345), child.getId(), new int[]{10, 2});
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 42), child.getId(), new int[]{11, 2});
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 42), child.getId(), new int[]{12, 2});
        placeEmptyOwnedRow(4, 0, 19);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 1.0, 3);
        plugin.onBirth(child, environment);

        List<MutationRecord> records = child.getBirthMutations();
        assertThat(records).hasSize(1);
        MutationRecord record = records.get(0);
        assertThat(record.pluginClass()).isEqualTo(GeneDuplicationPlugin.class.getName());
        assertThat(record.kind()).isEqualTo("duplication");
        assertThat(record.cells()).containsExactly(flatIndex(0, 4), flatIndex(1, 4), flatIndex(2, 4));
        assertThat(record.oldValues()).containsExactly(0, 0, 0);
        assertThat(record.newValues()).containsExactly(
                environment.getMolecule(0, 4).toInt(),
                environment.getMolecule(1, 4).toInt(),
                environment.getMolecule(2, 4).toInt());
        assertThat(record.newValues()[0])
                .as("the first target cell holds the copied label")
                .isEqualTo(environment.getMolecule(10, 2).toInt());
        assertThat(record.params())
                .as("the flat index of the first source cell")
                .containsExactly(flatIndex(10, 2));
        assertThat(record.dv()).isEqualTo(child.getDv());
    }

    @Test
    void aRunThatFindsNoLabelRecordsNothing() {
        for (int x = 0; x <= 14; x++) {
            environment.setMolecule(new Molecule(Config.TYPE_CODE, 42), child.getId(), new int[]{x, 2});
        }
        placeEmptyOwnedRow(4, 0, 14);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 1.0, 3);
        plugin.onBirth(child, environment);

        assertThat(child.getBirthMutations()).isNull();
    }

    @Test
    void aRunThatFindsNoNopRunLongEnoughRecordsNothing() {
        placeCodeRow(2, 0, 14, 5);
        for (int x = 0; x <= 14; x++) {
            Molecule mol = (x >= 7 && x <= 8)
                    ? new Molecule(Config.TYPE_CODE, 0)
                    : new Molecule(Config.TYPE_CODE, 42);
            environment.setMolecule(mol, child.getId(), new int[]{x, 4});
        }

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 1.0, 5);
        plugin.onBirth(child, environment);

        assertThat(child.getBirthMutations()).isNull();
    }

    // ---- Whole-block tests ----

    /** The molecule a block begins with. */
    private static Molecule label() {
        return new Molecule(Config.TYPE_LABEL, 12345);
    }

    /** The molecule of a register operand cell. */
    private static Molecule reg() {
        return new Molecule(Config.TYPE_REGISTER, 1);
    }

    /** The molecule of an opcode cell. */
    private static Molecule opcode(int id) {
        return new Molecule(Config.TYPE_CODE, id);
    }

    /** Places one cell of an organism's body. */
    private void place(int ownerId, int x, int y, Molecule molecule) {
        environment.setMolecule(molecule, ownerId, new int[]{x, y});
    }

    /**
     * Places a block of four cells, a label followed by one ADDR instruction, running from
     * {@code labelX} towards rising x.
     */
    private void placeRisingBlock(int ownerId, int labelX, int y) {
        place(ownerId, labelX, y, label());
        place(ownerId, labelX + 1, y, opcode(ADDR_OPCODE));
        place(ownerId, labelX + 2, y, reg());
        place(ownerId, labelX + 3, y, reg());
    }

    /**
     * Places a block of four cells, a label followed by one ADDR instruction, running from
     * {@code labelX} towards falling x.
     */
    private void placeFallingBlock(int ownerId, int labelX, int y) {
        place(ownerId, labelX, y, label());
        place(ownerId, labelX - 1, y, opcode(ADDR_OPCODE));
        place(ownerId, labelX - 2, y, reg());
        place(ownerId, labelX - 3, y, reg());
    }

    /**
     * Places a target row: an empty owned run of {@code runLength} cells from x=0, closed off by
     * four owned structure cells so that the run is the row's longest and the row's extent ends
     * behind it.
     */
    private void placeTargetRow(int ownerId, int y, int runLength) {
        for (int x = 0; x < runLength; x++) {
            place(ownerId, x, y, new Molecule(Config.TYPE_CODE, 0));
        }
        for (int x = runLength; x < runLength + 4; x++) {
            place(ownerId, x, y, new Molecule(Config.TYPE_STRUCTURE, 1));
        }
    }

    /** The molecules of one ADDR block, in the order the copy writes them. */
    private void assertBlockAt(int x, int y) {
        assertThat(environment.getMolecule(x, y).toInt()).isEqualTo(label().toInt());
        assertThat(environment.getMolecule(x + 1, y).toInt()).isEqualTo(opcode(ADDR_OPCODE).toInt());
        assertThat(environment.getMolecule(x + 2, y).toInt()).isEqualTo(reg().toInt());
        assertThat(environment.getMolecule(x + 3, y).toInt()).isEqualTo(reg().toInt());
    }

    @Test
    void aBlockThatFillsTheTargetRunExactlyIsCopiedWhole() {
        // One block of four cells at y=2 and a target run of exactly four cells at y=4
        placeRisingBlock(child.getId(), 10, 2);
        placeTargetRow(child.getId(), 4, 4);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 1.0, 4);
        plugin.onBirth(child, environment);

        assertBlockAt(0, 4);
        List<MutationRecord> records = child.getBirthMutations();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).cells())
                .containsExactly(flatIndex(0, 4), flatIndex(1, 4), flatIndex(2, 4), flatIndex(3, 4));
        assertThat(records.get(0).params()).containsExactly(flatIndex(10, 2));
    }

    @Test
    void aRunThatHoldsTheFirstOfTwoBlocksExactlyCopiesTheFirst() {
        // Two blocks of four cells at y=2, a target run of exactly four cells at y=4: the second
        // block's label stands on the first cell past the room
        placeRisingBlock(child.getId(), 10, 2);
        placeRisingBlock(child.getId(), 14, 2);
        placeTargetRow(child.getId(), 4, 4);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 1.0, 4);
        plugin.onBirth(child, environment);

        assertBlockAt(0, 4);
        List<MutationRecord> records = child.getBirthMutations();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).cells())
                .containsExactly(flatIndex(0, 4), flatIndex(1, 4), flatIndex(2, 4), flatIndex(3, 4));
    }

    @Test
    void aRunThatHoldsTheFirstBlockAndPartOfTheSecondCopiesOnlyTheFirst() {
        // Two blocks of four cells at y=2, a target run of six cells at y=4
        placeRisingBlock(child.getId(), 10, 2);
        placeRisingBlock(child.getId(), 14, 2);
        placeTargetRow(child.getId(), 4, 6);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 1.0, 4);
        plugin.onBirth(child, environment);

        assertBlockAt(0, 4);
        assertThat(environment.getMolecule(4, 4).isEmpty())
                .as("the second block's label is not copied").isTrue();
        assertThat(environment.getMolecule(5, 4).isEmpty())
                .as("the rest of the run stays empty").isTrue();

        List<MutationRecord> records = child.getBirthMutations();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).cells())
                .containsExactly(flatIndex(0, 4), flatIndex(1, 4), flatIndex(2, 4), flatIndex(3, 4));
        assertThat(records.get(0).params()).containsExactly(flatIndex(10, 2));
    }

    @Test
    void aRunThatCannotHoldTheFirstBlockIsNotWrittenTo() {
        // Two blocks of four cells at y=2, a target run of three cells at y=4
        placeRisingBlock(child.getId(), 10, 2);
        placeRisingBlock(child.getId(), 14, 2);
        placeTargetRow(child.getId(), 4, 3);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 1.0, 3);
        plugin.onBirth(child, environment);

        for (int x = 0; x < 3; x++) {
            assertThat(environment.getMolecule(x, 4).isEmpty())
                    .as("cell (%d,4) of the target run stays empty", x).isTrue();
        }
        assertThat(child.getBirthMutations()).isNull();
    }

    @Test
    void aRunThatHoldsTwoBlocksAndAHalfCopiesTwoBlocks() {
        // Three blocks of four cells at y=2, a target run of ten cells at y=4
        placeRisingBlock(child.getId(), 10, 2);
        placeRisingBlock(child.getId(), 14, 2);
        placeRisingBlock(child.getId(), 18, 2);
        placeTargetRow(child.getId(), 4, 10);

        IRandomProvider rng = new SeededRandomProvider(43L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 1.0, 4);
        plugin.onBirth(child, environment);

        assertBlockAt(0, 4);
        assertBlockAt(4, 4);
        assertThat(environment.getMolecule(8, 4).isEmpty())
                .as("the third block does not fit and is not begun").isTrue();
        assertThat(environment.getMolecule(9, 4).isEmpty())
                .as("the rest of the run stays empty").isTrue();

        List<MutationRecord> records = child.getBirthMutations();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).cells()).containsExactly(
                flatIndex(0, 4), flatIndex(1, 4), flatIndex(2, 4), flatIndex(3, 4),
                flatIndex(4, 4), flatIndex(5, 4), flatIndex(6, 4), flatIndex(7, 4));
        assertThat(records.get(0).params()).containsExactly(flatIndex(10, 2));
    }

    @Test
    void theCutFollowsADirectionVectorTowardsFallingCoordinates() {
        // The same three blocks and the same room, laid out for a DV of (-1, 0): the blocks run from
        // x=21 towards falling x, and the copy walks the target run from its far end downwards.
        Organism negChild = Organism.restore(3, 9)
                .parentId(1)
                .ip(new int[]{0, 0})
                .dv(new int[]{-1, 0})
                .initialPosition(new int[]{0, 0})
                .energy(5000)
                .build(simulation);
        simulation.addOrganism(negChild);
        int negId = negChild.getId();

        placeFallingBlock(negId, 21, 2);
        placeFallingBlock(negId, 17, 2);
        placeFallingBlock(negId, 13, 2);
        placeTargetRow(negId, 4, 10);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 1.0, 4);
        plugin.onBirth(negChild, environment);

        assertThat(environment.getMolecule(7, 4).toInt()).isEqualTo(label().toInt());
        assertThat(environment.getMolecule(6, 4).toInt()).isEqualTo(opcode(ADDR_OPCODE).toInt());
        assertThat(environment.getMolecule(5, 4).toInt()).isEqualTo(reg().toInt());
        assertThat(environment.getMolecule(4, 4).toInt()).isEqualTo(reg().toInt());
        assertThat(environment.getMolecule(3, 4).toInt()).isEqualTo(label().toInt());
        assertThat(environment.getMolecule(2, 4).toInt()).isEqualTo(opcode(ADDR_OPCODE).toInt());
        assertThat(environment.getMolecule(1, 4).toInt()).isEqualTo(reg().toInt());
        assertThat(environment.getMolecule(0, 4).toInt()).isEqualTo(reg().toInt());
        assertThat(environment.getMolecule(8, 4).isEmpty())
                .as("the third block does not fit and is not begun").isTrue();
        assertThat(environment.getMolecule(9, 4).isEmpty())
                .as("the rest of the run stays empty").isTrue();

        List<MutationRecord> records = negChild.getBirthMutations();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).cells()).containsExactly(
                flatIndex(7, 4), flatIndex(6, 4), flatIndex(5, 4), flatIndex(4, 4),
                flatIndex(3, 4), flatIndex(2, 4), flatIndex(1, 4), flatIndex(0, 4));
        assertThat(records.get(0).params()).containsExactly(flatIndex(21, 2));
    }

    @Test
    void aLabelInsideAnOperandListIsNoBlockBoundary() {
        // y=2: one ADDR block at x=10..13, then a block whose SETV carries a LABEL molecule as the
        // first component of its vector operand (x=17). The target run of eight cells ends at that
        // very cell, in the middle of the SETV, so the copy is cut back to the block before it.
        int id = child.getId();
        placeRisingBlock(id, 10, 2);
        place(id, 14, 2, label());
        place(id, 15, 2, opcode(SETV_OPCODE));
        place(id, 16, 2, reg());
        place(id, 17, 2, label());
        place(id, 18, 2, new Molecule(Config.TYPE_DATA, 7));
        placeTargetRow(id, 4, 8);

        IRandomProvider rng = new SeededRandomProvider(1L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 1.0, 4);
        plugin.onBirth(child, environment);

        assertBlockAt(0, 4);
        for (int x = 4; x < 8; x++) {
            assertThat(environment.getMolecule(x, 4).isEmpty())
                    .as("cell (%d,4) is not written: the label in the vector operand begins no block", x)
                    .isTrue();
        }

        List<MutationRecord> records = child.getBirthMutations();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).cells())
                .containsExactly(flatIndex(0, 4), flatIndex(1, 4), flatIndex(2, 4), flatIndex(3, 4));
        assertThat(records.get(0).params()).containsExactly(flatIndex(10, 2));
    }

    @Test
    void aRunLongerThanTheRestOfTheLineTakesItAsItStands() {
        // One block of four cells at y=2, a target run of eight cells at y=4
        placeRisingBlock(child.getId(), 10, 2);
        placeTargetRow(child.getId(), 4, 8);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 1.0, 4);
        plugin.onBirth(child, environment);

        assertBlockAt(0, 4);
        for (int x = 4; x < 8; x++) {
            assertThat(environment.getMolecule(x, 4).isEmpty())
                    .as("cell (%d,4) beyond the source stays empty", x).isTrue();
        }

        List<MutationRecord> records = child.getBirthMutations();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).cells())
                .containsExactly(flatIndex(0, 4), flatIndex(1, 4), flatIndex(2, 4), flatIndex(3, 4));
        assertThat(records.get(0).params()).containsExactly(flatIndex(10, 2));
    }

    @Test
    void isStateless() {
        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 0.1, 5);

        byte[] state = plugin.saveState();
        assertThat(state).isEmpty();

        // loadState should not throw
        plugin.loadState(new byte[0]);
    }
}
