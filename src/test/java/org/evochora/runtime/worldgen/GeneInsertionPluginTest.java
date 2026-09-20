package org.evochora.runtime.worldgen;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.Instruction.OperandSource;
import org.evochora.runtime.isa.RegisterBank;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.MutationRecord;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;
import org.evochora.runtime.worldgen.GeneInsertionPlugin.ArgumentConfig;
import org.evochora.runtime.worldgen.GeneInsertionPlugin.DataConfig;
import org.evochora.runtime.worldgen.GeneInsertionPlugin.InstructionEntry;
import org.evochora.runtime.worldgen.GeneInsertionPlugin.LabelEntry;
import org.evochora.runtime.worldgen.GeneInsertionPlugin.RegisterConfig;
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
 * Unit tests for {@link GeneInsertionPlugin}.
 * <p>
 * Tests use realistic genome layouts: boundary CODE molecules owned by the child
 * define scan line extent, with naturally empty (moleculeInt == 0, owner == 0) cells
 * between them providing NOP areas for mutation insertion.
 */
@Tag("unit")
class GeneInsertionPluginTest {

    private Simulation simulation;
    private Environment environment;
    private Organism child;

    private static final int LABEL_HASH_A = 11111;

    /**
     * A value that differs from {@link #LABEL_HASH_A} in three bits, one more than the label
     * index's default Hamming tolerance, so that a reference to it does not address A.
     */
    private static final int LABEL_HASH_X = LABEL_HASH_A ^ 0b0000111;

    /** Left boundary x-coordinate for the standard scan line. */
    private static final int LEFT = 2;
    /**
     * Right boundary x-coordinate for the standard scan line. The two boundaries must span at
     * most half the 32-cell axis: beyond that the plugin takes the arc wrapping around the world
     * edge as the body, and the NOP gap between them would count as outside.
     */
    private static final int RIGHT = 16;
    /** Y-coordinate for the standard scan line. */
    private static final int Y = 5;

    @BeforeAll
    static void initInstructions() {
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
     * Creates a scan line at the given y coordinate with NOP gap between boundary cells.
     * Places CODE molecules at leftX and rightX, owned by the child.
     * Cells between leftX+1 and rightX-1 are naturally empty (moleculeInt == 0).
     */
    private void createScanLine(int leftX, int rightX, int y) {
        placeCode(leftX, y);
        placeCode(rightX, y);
    }

    /**
     * Clears the NOP gap between boundary cells, resetting to empty/unowned state.
     * Used in multi-iteration tests to reset between runs.
     */
    private void clearNopGap(int leftX, int rightX, int y) {
        Molecule empty = new Molecule(Config.TYPE_CODE, 0);
        for (int x = leftX + 1; x < rightX; x++) {
            environment.setMolecule(empty, 0, new int[]{x, y});
        }
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
     * Places a molecule of the given type and value at the given position, owned by the child.
     */
    private void place(int x, int y, int type, int value) {
        environment.setMolecule(new Molecule(type, value), child.getId(), new int[]{x, y});
    }

    /** The opcode of the instruction the label entry tests insert. */
    private static int setiId() {
        Integer id = Instruction.getInstructionIdByName("SETI");
        assertThat(id).isNotNull();
        return id;
    }

    /** The opcode of the jump a label entry's chain ends with. */
    private static int jmpiId() {
        Integer id = Instruction.getInstructionIdByName("JMPI");
        assertThat(id).isNotNull();
        return id;
    }

    /**
     * Creates a label entry that inserts a SETI, so that its chain is six cells long:
     * LABEL, CODE, REGISTER, DATA, CODE, LABELREF.
     */
    private LabelEntry createLabelEntry() {
        int seti = setiId();
        RegisterConfig regConfig = new RegisterConfig(List.of(new int[]{0, 0, 7}));
        DataConfig dataConfig = new DataConfig(0, 255);
        ArgumentConfig argConfig = new ArgumentConfig(regConfig, null, dataConfig, null, null);
        return new LabelEntry(
                List.of(seti),
                List.of(Instruction.getOperandSourcesById(seti)),
                1.0,
                argConfig);
    }

    /** Length of the chain {@link #createLabelEntry} produces. */
    private static final int LABEL_CHAIN_LENGTH = 6;

    /** Places a jump to the given value, two cells. */
    private void placeJump(int x, int y, int target) {
        place(x, y, Config.TYPE_CODE, jmpiId());
        place(x + 1, y, Config.TYPE_LABELREF, target);
    }

    /**
     * Runs a label entry and returns the record it wrote, or null if it placed nothing.
     */
    private MutationRecord insertBeforeLabel(long seed) {
        IRandomProvider rng = new SeededRandomProvider(seed);
        GeneInsertionPlugin plugin = new GeneInsertionPlugin(rng, 1.0, List.of(createLabelEntry()));
        plugin.mutate(child, environment);
        List<MutationRecord> records = child.getBirthMutations();
        return records == null ? null : records.get(0);
    }

    /**
     * Asserts that a label entry's chain stands at the given start and that the label it copied,
     * at {@code labelX}, now carries the value the chain jumps to, one bit from the copied one.
     *
     * @return The value the copied label was renamed to.
     */
    private int assertInsertedBeforeLabel(MutationRecord record, int startX, int labelX, int copiedValue) {
        assertThat(record).isNotNull();
        assertThat(record.pluginClass()).isEqualTo(GeneInsertionPlugin.class.getName());
        assertThat(record.kind()).isEqualTo("label-insertion");
        assertThat(record.params()).hasSize(2);
        assertThat(record.params()[0]).isEqualTo(copiedValue);
        int renamed = (int) record.params()[1];
        assertThat(Integer.bitCount(renamed ^ copiedValue)).as("the renamed value is one bit away").isEqualTo(1);
        assertThat(record.cells()).containsExactly(
                flatIndex(startX, Y), flatIndex(startX + 1, Y), flatIndex(startX + 2, Y),
                flatIndex(startX + 3, Y), flatIndex(startX + 4, Y), flatIndex(startX + 5, Y),
                flatIndex(labelX, Y));
        assertThat(record.dv()).isEqualTo(child.getDv());

        assertThat(environment.getMolecule(startX, Y).type()).isEqualTo(Config.TYPE_LABEL);
        assertThat(environment.getMolecule(startX, Y).value()).isEqualTo(copiedValue);
        assertThat(environment.getMolecule(startX + 1, Y).type()).isEqualTo(Config.TYPE_CODE);
        assertThat(environment.getMolecule(startX + 1, Y).value()).isEqualTo(setiId());
        assertThat(environment.getMolecule(startX + 2, Y).type()).isEqualTo(Config.TYPE_REGISTER);
        assertThat(environment.getMolecule(startX + 3, Y).type()).isEqualTo(Config.TYPE_DATA);
        assertThat(environment.getMolecule(startX + 4, Y).type()).isEqualTo(Config.TYPE_CODE);
        assertThat(environment.getMolecule(startX + 4, Y).value()).isEqualTo(jmpiId());
        assertThat(environment.getMolecule(startX + 5, Y).type()).isEqualTo(Config.TYPE_LABELREF);
        assertThat(environment.getMolecule(startX + 5, Y).value()).isEqualTo(renamed);

        assertThat(environment.getMolecule(labelX, Y).type()).isEqualTo(Config.TYPE_LABEL);
        assertThat(environment.getMolecule(labelX, Y).value()).as("the block's label").isEqualTo(renamed);
        return renamed;
    }

    /** Asserts that the cells from {@code fromX} up to but not including {@code toX} are empty. */
    private void assertEmpty(int fromX, int toX) {
        for (int x = fromX; x < toX; x++) {
            assertThat(environment.getMolecule(x, Y).isEmpty())
                    .as("Cell (%d,%d) should remain empty", x, Y).isTrue();
        }
    }

    /**
     * Creates an instruction entry for SETI (REGISTER, IMMEDIATE) with DR bank and data range.
     */
    private InstructionEntry createSetiEntry() {
        Integer setiId = Instruction.getInstructionIdByName("SETI");
        assertThat(setiId).isNotNull();
        List<OperandSource> sources = Instruction.getOperandSourcesById(setiId);

        RegisterConfig regConfig = new RegisterConfig(List.of(new int[]{0, 0, 7}));
        DataConfig dataConfig = new DataConfig(0, 255);
        ArgumentConfig argConfig = new ArgumentConfig(regConfig, null, dataConfig, null, null);

        return new InstructionEntry(
                List.of(setiId),
                List.of(sources),
                1.0,
                argConfig
        );
    }

    /**
     * Creates an instruction entry for ADDR (REGISTER, REGISTER) with DR bank.
     */
    private InstructionEntry createAddrEntry() {
        Integer addrId = Instruction.getInstructionIdByName("ADDR");
        assertThat(addrId).isNotNull();
        List<OperandSource> sources = Instruction.getOperandSourcesById(addrId);

        RegisterConfig regConfig = new RegisterConfig(List.of(new int[]{0, 0, 7}));
        ArgumentConfig argConfig = new ArgumentConfig(regConfig, null, null, null, null);

        return new InstructionEntry(
                List.of(addrId),
                List.of(sources),
                1.0,
                argConfig
        );
    }

    @Test
    void insertsInstructionIntoNopArea() {
        createScanLine(LEFT, RIGHT, Y);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneInsertionPlugin plugin = new GeneInsertionPlugin(rng, 1.0, List.of(createSetiEntry()));
        plugin.mutate(child, environment);

        // At least one cell in the NOP gap should now be non-empty
        boolean foundNonEmpty = false;
        for (int x = LEFT + 1; x < RIGHT; x++) {
            if (!environment.getMolecule(x, Y).isEmpty()) {
                foundNonEmpty = true;
                break;
            }
        }
        assertThat(foundNonEmpty).as("Instruction chain should be inserted into NOP area").isTrue();
    }

    @Test
    void instructionHasCorrectMoleculeTypes() {
        // SETI has operands: REGISTER, IMMEDIATE -> chain = [CODE, REGISTER, DATA]
        createScanLine(LEFT, RIGHT, Y);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneInsertionPlugin plugin = new GeneInsertionPlugin(rng, 1.0, List.of(createSetiEntry()));
        plugin.mutate(child, environment);

        // Find the inserted chain: a CODE molecule followed by REGISTER and DATA
        int chainStart = -1;
        for (int x = LEFT + 1; x < RIGHT; x++) {
            Molecule mol = environment.getMolecule(x, Y);
            if (mol.type() == Config.TYPE_CODE && mol.value() != 0) {
                chainStart = x;
                break;
            }
        }
        assertThat(chainStart).as("Should find a CODE opcode molecule").isGreaterThan(LEFT);

        // Verify the chain: CODE, REGISTER, DATA
        assertThat(environment.getMolecule(chainStart, Y).type()).isEqualTo(Config.TYPE_CODE);
        assertThat(environment.getMolecule(chainStart + 1, Y).type()).isEqualTo(Config.TYPE_REGISTER);
        assertThat(environment.getMolecule(chainStart + 2, Y).type()).isEqualTo(Config.TYPE_DATA);
    }

    @Test
    void respectsRegisterBankConfig() {
        createScanLine(LEFT, RIGHT, Y);

        // Run many times, check DR bank values are 0-7
        for (int seed = 0; seed < 50; seed++) {
            clearNopGap(LEFT, RIGHT, Y);

            IRandomProvider rng = new SeededRandomProvider(seed);
            GeneInsertionPlugin plugin = new GeneInsertionPlugin(rng, 1.0, List.of(createAddrEntry()));
            plugin.mutate(child, environment);

            // Find REGISTER molecules
            for (int x = LEFT + 1; x < RIGHT; x++) {
                Molecule mol = environment.getMolecule(x, Y);
                if (mol.type() == Config.TYPE_REGISTER) {
                    assertThat(mol.value()).as("DR bank register should be 0-7 (seed=%d)", seed).isBetween(0, 7);
                }
            }
        }
    }

    @Test
    void respectsDataRange() {
        createScanLine(LEFT, RIGHT, Y);

        DataConfig dataConfig = new DataConfig(10, 50);
        RegisterConfig regConfig = new RegisterConfig(List.of(new int[]{0, 0, 7}));
        ArgumentConfig argConfig = new ArgumentConfig(regConfig, null, dataConfig, null, null);

        Integer setiId = Instruction.getInstructionIdByName("SETI");
        InstructionEntry entry = new InstructionEntry(
                List.of(setiId),
                List.of(Instruction.getOperandSourcesById(setiId)),
                1.0,
                argConfig
        );

        for (int seed = 0; seed < 50; seed++) {
            clearNopGap(LEFT, RIGHT, Y);

            IRandomProvider rng = new SeededRandomProvider(seed);
            GeneInsertionPlugin plugin = new GeneInsertionPlugin(rng, 1.0, List.of(entry));
            plugin.mutate(child, environment);

            for (int x = LEFT + 1; x < RIGHT; x++) {
                Molecule mol = environment.getMolecule(x, Y);
                if (mol.type() == Config.TYPE_DATA) {
                    assertThat(mol.value()).as("DATA value should be in [10, 50] (seed=%d)", seed).isBetween(10, 50);
                }
            }
        }
    }

    @Test
    void labelRefUsesExistingHash() {
        // Place a label as the left boundary of the scan line
        placeLabel(LEFT, Y, LABEL_HASH_A);
        placeCode(RIGHT, Y);

        Integer jmpiId = Instruction.getInstructionIdByName("JMPI");
        assertThat(jmpiId).isNotNull();
        List<OperandSource> sources = Instruction.getOperandSourcesById(jmpiId);

        ArgumentConfig argConfig = new ArgumentConfig(null, null, null, "existing", null);
        InstructionEntry entry = new InstructionEntry(
                List.of(jmpiId),
                List.of(sources),
                1.0,
                argConfig
        );

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneInsertionPlugin plugin = new GeneInsertionPlugin(rng, 1.0, List.of(entry));
        plugin.mutate(child, environment);

        // Find the LABELREF molecule
        boolean foundLabelRef = false;
        for (int x = LEFT + 1; x < RIGHT; x++) {
            Molecule mol = environment.getMolecule(x, Y);
            if (mol.type() == Config.TYPE_LABELREF) {
                assertThat(mol.value()).as("LABELREF should use existing label hash").isEqualTo(LABEL_HASH_A);
                foundLabelRef = true;
            }
        }
        assertThat(foundLabelRef).as("Should insert a LABELREF molecule").isTrue();
    }

    // ---- Label entry tests ----

    @Test
    void aLabelEntryPutsAnInstructionInFrontOfABlock() {
        // LABEL A heads a block that jumps back to A; the empty run behind that jump holds the chain.
        placeLabel(2, Y, LABEL_HASH_A);
        placeJump(3, Y, LABEL_HASH_A);
        placeCode(15, Y);

        MutationRecord record = insertBeforeLabel(42L);

        // x=5..14 is the only empty run, so the chain starts at its first cell
        assertInsertedBeforeLabel(record, 5, 2, LABEL_HASH_A);
        assertThat(environment.getMolecule(4, Y).value())
                .as("the reference to A is untouched and now finds the new label").isEqualTo(LABEL_HASH_A);
    }

    /**
     * An inserted instruction that takes a label operand refers to the renamed block, never to the
     * label the chain opens with: a call of that label would call itself without end.
     */
    @Test
    void aCallInsertedByALabelEntryRefersToTheRenamedBlock() {
        placeLabel(2, Y, LABEL_HASH_A);
        placeJump(3, Y, LABEL_HASH_A);
        placeCode(15, Y);
        Integer call = Instruction.getInstructionIdByName("CALL");
        assertThat(call).isNotNull();
        LabelEntry entry = new LabelEntry(
                List.of(call),
                List.of(Instruction.getOperandSourcesById(call)),
                1.0,
                new ArgumentConfig(null, null, null, "existing", null));
        GeneInsertionPlugin plugin = new GeneInsertionPlugin(new SeededRandomProvider(42L), 1.0, List.of(entry));

        plugin.mutate(child, environment);

        // The chain LABEL A, CALL, LABELREF, JMPI, LABELREF starts at x=5; both references carry A'
        List<MutationRecord> records = child.getBirthMutations();
        assertThat(records).hasSize(1);
        int renamed = (int) records.get(0).params()[1];
        assertThat(environment.getMolecule(5, Y).value()).isEqualTo(LABEL_HASH_A);
        assertThat(environment.getMolecule(7, Y).type()).isEqualTo(Config.TYPE_LABELREF);
        assertThat(environment.getMolecule(7, Y).value()).as("the inserted call's target").isEqualTo(renamed);
        assertThat(environment.getMolecule(9, Y).value()).as("the closing jump's target").isEqualTo(renamed);
    }

    @Test
    void aLabelALocationInstructionAddressesIsNotCopied() {
        // A is where SKJI sends the data pointer, so moving its value would move that place
        Integer skji = Instruction.getInstructionIdByName("SKJI");
        assertThat(skji).isNotNull();
        placeLabel(2, Y, LABEL_HASH_A);
        place(3, Y, Config.TYPE_CODE, skji);
        place(4, Y, Config.TYPE_LABELREF, LABEL_HASH_A);
        placeJump(5, Y, LABEL_HASH_X);
        placeCode(16, Y);

        assertThat(insertBeforeLabel(42L)).isNull();
        assertEmpty(7, 16);
        assertThat(environment.getMolecule(2, Y).value()).isEqualTo(LABEL_HASH_A);
    }

    @Test
    void aLabelNoJumpAddressesIsNotCopied() {
        placeLabel(2, Y, LABEL_HASH_A);
        placeJump(3, Y, LABEL_HASH_X);
        placeCode(15, Y);

        assertThat(insertBeforeLabel(42L)).isNull();
        assertEmpty(5, 15);
        assertThat(environment.getMolecule(2, Y).value()).isEqualTo(LABEL_HASH_A);
    }

    @Test
    void theChainGoesWhereExecutionDoesNotRunOnInto() {
        // x=6..11 lies behind an ordinary instruction; x=14..20 lies behind the unconditional jump
        for (long seed = 0; seed < 20; seed++) {
            setUp();
            placeLabel(2, Y, LABEL_HASH_A);
            place(3, Y, Config.TYPE_CODE, setiId());
            place(4, Y, Config.TYPE_REGISTER, 0);
            place(5, Y, Config.TYPE_DATA, 1);
            placeJump(12, Y, LABEL_HASH_A);
            placeCode(21, Y);

            MutationRecord record = insertBeforeLabel(seed);

            assertInsertedBeforeLabel(record, 14, 2, LABEL_HASH_A);
            assertEmpty(6, 12);
        }
    }

    @Test
    void aBodyWhoseOnlyRunExecutionPassesThroughGetsNothing() {
        // The only empty run lies between an ordinary instruction and the jump behind it
        placeLabel(2, Y, LABEL_HASH_A);
        place(3, Y, Config.TYPE_CODE, setiId());
        place(4, Y, Config.TYPE_REGISTER, 0);
        place(5, Y, Config.TYPE_DATA, 1);
        placeJump(12, Y, LABEL_HASH_A);

        assertThat(insertBeforeLabel(42L)).isNull();
        assertEmpty(6, 12);
        assertThat(environment.getMolecule(2, Y).value())
                .as("a chain that found no room leaves the label as it is").isEqualTo(LABEL_HASH_A);
    }

    /**
     * The renamed value keeps clear of the body's other labels and of the references to them.
     * Labels A and X stand three bits apart, in bits 0 to 2: flipping one of those bits in either
     * would bring it within the tolerance of the references to the other.
     */
    @Test
    void theRenamedValueDrawsNoReferenceAwayFromAnotherLabel() {
        for (long seed = 0; seed < 40; seed++) {
            setUp();
            placeLabel(2, Y, LABEL_HASH_A);
            placeJump(3, Y, LABEL_HASH_X);
            placeLabel(5, Y, LABEL_HASH_X);
            placeJump(6, Y, LABEL_HASH_A);
            placeCode(18, Y);

            MutationRecord record = insertBeforeLabel(seed);

            assertThat(record).isNotNull();
            int flipped = (int) (record.params()[0] ^ record.params()[1]);
            assertThat(Integer.bitCount(flipped)).isEqualTo(1);
            assertThat(flipped & 0b111).as("seed %d flipped one of the bits A and X differ in", seed).isZero();
        }
    }

    /**
     * The walk that asks whether execution runs on into a run follows the direction vector around
     * the world edge, as the code it reads does.
     */
    @Test
    void aRunBehindAJumpAcrossTheWorldEdgeIsUsed() {
        // A at x=28, its jump at x=30 with the reference at x=31; the body owns a cell at x=10, so
        // the arc runs from 28 around the edge to 10 and the empty run x=0..9 lies behind the jump.
        placeLabel(28, Y, LABEL_HASH_A);
        placeJump(30, Y, LABEL_HASH_A);
        placeCode(10, Y);

        MutationRecord record = insertBeforeLabel(42L);

        assertInsertedBeforeLabel(record, 0, 28, LABEL_HASH_A);
    }

    @Test
    void aRunTooShortForTheWholeChainIsNotUsed() {
        // The empty run holds a bare label but not the chain of six cells
        placeLabel(2, Y, LABEL_HASH_A);
        placeJump(3, Y, LABEL_HASH_A);
        placeCode(8, Y);

        assertThat(LABEL_CHAIN_LENGTH).isGreaterThan(3);
        assertThat(insertBeforeLabel(42L)).isNull();
        assertEmpty(5, 8);
        assertThat(environment.getMolecule(2, Y).value()).isEqualTo(LABEL_HASH_A);
    }

    @Test
    void aLabelInsideAnOperandListIsNotCopied() {
        // The LABEL at x=5 stands in SETI's immediate slot, so it opens no block. It carries a value
        // of its own that a jump addresses, and still only A, which opens a block, is copied.
        for (long seed = 0; seed < 10; seed++) {
            setUp();
            placeLabel(2, Y, LABEL_HASH_A);
            place(3, Y, Config.TYPE_CODE, setiId());
            place(4, Y, Config.TYPE_REGISTER, 0);
            placeLabel(5, Y, LABEL_HASH_X);
            placeJump(6, Y, LABEL_HASH_A);
            placeJump(8, Y, LABEL_HASH_X);
            placeCode(20, Y);

            MutationRecord record = insertBeforeLabel(seed);

            assertThat(record).isNotNull();
            assertThat(record.params()[0]).as("seed %d", seed).isEqualTo(LABEL_HASH_A);
        }
    }

    @Test
    void aLabelEntryFollowsANegativeDirectionVector() {
        // Execution runs from high x to low x: A at x=20, its jump at x=19 with the reference at
        // x=18, then the empty run x=6..17 down to the boundary at x=5.
        Organism negChild = Organism.restore(3, 9)
                .parentId(1)
                .ip(new int[]{0, 0})
                .dv(new int[]{-1, 0})
                .initialPosition(new int[]{0, 0})
                .energy(5000)
                .build(simulation);
        simulation.addOrganism(negChild);
        int negId = negChild.getId();
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, LABEL_HASH_A), negId, new int[]{20, Y});
        environment.setMolecule(new Molecule(Config.TYPE_CODE, jmpiId()), negId, new int[]{19, Y});
        environment.setMolecule(new Molecule(Config.TYPE_LABELREF, LABEL_HASH_A), negId, new int[]{18, Y});
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 42), negId, new int[]{5, Y});

        GeneInsertionPlugin plugin = new GeneInsertionPlugin(new SeededRandomProvider(42L), 1.0,
                List.of(createLabelEntry()));
        plugin.mutate(negChild, environment);

        List<MutationRecord> records = negChild.getBirthMutations();
        assertThat(records).hasSize(1);
        int renamed = (int) records.get(0).params()[1];
        // The chain occupies the six cells of the run with the smallest coordinates and reads downwards
        assertThat(environment.getMolecule(11, Y).type()).isEqualTo(Config.TYPE_LABEL);
        assertThat(environment.getMolecule(11, Y).value()).isEqualTo(LABEL_HASH_A);
        assertThat(environment.getMolecule(10, Y).value()).isEqualTo(setiId());
        assertThat(environment.getMolecule(7, Y).value()).isEqualTo(jmpiId());
        assertThat(environment.getMolecule(6, Y).type()).isEqualTo(Config.TYPE_LABELREF);
        assertThat(environment.getMolecule(6, Y).value()).isEqualTo(renamed);
        assertThat(environment.getMolecule(20, Y).value()).isEqualTo(renamed);
    }

    // ---- Configuration tests ----

    @Test
    void aLabelEntryRejectsBitflips() {
        String text = """
                mutationRate = 1.0
                entries = [
                  { type = "label", weight = 1, bitflips = 2, instructions = ["SETI"],
                    args { REGISTER { range = [0, 7] }, DATA { min = 0, max = 255 } } }
                ]
                """;
        assertThatThrownBy(() -> new GeneInsertionPlugin(
                new SeededRandomProvider(42L), ConfigFactory.parseString(text)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bitflips");
    }

    @Test
    void aLabelEntryThatNamesAConditionalIsRejected() {
        String text = """
                mutationRate = 1.0
                entries = [
                  { type = "label", weight = 1, instructions = ["SETI", "IFI"],
                    args { REGISTER { range = [0, 7] }, DATA { min = 0, max = 255 } } }
                ]
                """;
        assertThatThrownBy(() -> new GeneInsertionPlugin(
                new SeededRandomProvider(42L), ConfigFactory.parseString(text)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IFI");
    }

    @Test
    void aLabelEntryThatNamesAJumpOrAReturnIsRejected() {
        for (String name : List.of("JMPI", "JMPR", "JMPS", "RET")) {
            String text = """
                    mutationRate = 1.0
                    entries = [
                      { type = "label", weight = 1, instructions = ["SETI", "%s"],
                        args { REGISTER { range = [0, 7] }, DATA { min = 0, max = 255 }, LABELREF = "existing" } }
                    ]
                    """.formatted(name);
            assertThatThrownBy(() -> new GeneInsertionPlugin(
                    new SeededRandomProvider(42L), ConfigFactory.parseString(text)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(name);
        }
    }

    @Test
    void aWildcardLabelEntryInsertsOnlyWhatExecutionGoesOnBehind() {
        String text = """
                mutationRate = 1.0
                entries = [
                  { type = "label", weight = 1, instructions = "*",
                    args { REGISTER { range = [0, 7] }, LOCATION_REGISTER { range = [0, 3] },
                           DATA { min = 0, max = 255 }, LABELREF = "existing", VECTOR = "unit" } }
                ]
                """;
        for (long seed = 0; seed < 60; seed++) {
            setUp();
            placeLabel(2, Y, LABEL_HASH_A);
            placeJump(3, Y, LABEL_HASH_A);
            placeCode(16, Y);
            GeneInsertionPlugin plugin = new GeneInsertionPlugin(
                    new SeededRandomProvider(seed), ConfigFactory.parseString(text));

            plugin.mutate(child, environment);

            assertThat(child.getBirthMutations()).as("seed %d", seed).hasSize(1);
            int insertedOpcode = environment.getMolecule(6, Y).value();
            assertThat(Instruction.getFamilyById(insertedOpcode))
                    .as("seed %d inserted %s", seed, Instruction.getInstructionNameById(insertedOpcode))
                    .isNotEqualTo(org.evochora.runtime.isa.Family.CONDITIONAL);
            assertThat(Instruction.neverFallsThrough(insertedOpcode))
                    .as("seed %d inserted %s", seed, Instruction.getInstructionNameById(insertedOpcode))
                    .isFalse();
        }
    }

    @Test
    void aLabelEntryNeedsAnInstructionToInsert() {
        String text = """
                mutationRate = 1.0
                entries = [
                  { type = "label", weight = 1 }
                ]
                """;
        assertThatThrownBy(() -> new GeneInsertionPlugin(
                new SeededRandomProvider(42L), ConfigFactory.parseString(text)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instructions");
    }

    @Test
    void skipsWhenNoNopArea() {
        // Fill entire scan line with non-empty code (no NOP gaps)
        for (int x = 0; x < 25; x++) {
            placeCode(x, Y);
        }

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneInsertionPlugin plugin = new GeneInsertionPlugin(rng, 1.0, List.of(createSetiEntry()));
        // Should not throw
        plugin.mutate(child, environment);

        // Everything should still be CODE:42
        for (int x = 0; x < 25; x++) {
            assertThat(environment.getMolecule(x, Y).value()).as("Cell (%d,%d) should be unchanged", x, Y).isEqualTo(42);
        }
    }

    @Test
    void zeroRateNeverMutates() {
        createScanLine(LEFT, RIGHT, Y);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneInsertionPlugin plugin = new GeneInsertionPlugin(rng, 0.0, List.of(createSetiEntry()));
        plugin.onBirth(child, environment);

        // NOP gap should remain empty
        for (int x = LEFT + 1; x < RIGHT; x++) {
            assertThat(environment.getMolecule(x, Y).isEmpty()).as("Cell (%d,%d) should remain empty", x, Y).isTrue();
        }
    }

    @Test
    void vectorArgumentGeneratesUnitVector() {
        createScanLine(LEFT, RIGHT, Y);

        // SEKI (VECTOR) — one vector operand
        Integer sekiId = Instruction.getInstructionIdByName("SEKI");
        assertThat(sekiId).isNotNull();
        List<OperandSource> sources = Instruction.getOperandSourcesById(sekiId);
        assertThat(sources).contains(OperandSource.VECTOR);

        ArgumentConfig argConfig = new ArgumentConfig(null, null, null, null, "unit");
        InstructionEntry entry = new InstructionEntry(
                List.of(sekiId),
                List.of(sources),
                1.0,
                argConfig
        );

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneInsertionPlugin plugin = new GeneInsertionPlugin(rng, 1.0, List.of(entry));
        plugin.mutate(child, environment);

        // Find the CODE molecule (opcode), then read the 2 DATA molecules after it (2D environment)
        int chainStart = -1;
        for (int x = LEFT + 1; x < RIGHT; x++) {
            Molecule mol = environment.getMolecule(x, Y);
            if (mol.type() == Config.TYPE_CODE && mol.value() != 0) {
                chainStart = x;
                break;
            }
        }
        assertThat(chainStart).as("Should find opcode").isGreaterThan(LEFT);

        // Read 2D vector (2 DATA molecules after the opcode)
        Molecule v0 = environment.getMolecule(chainStart + 1, Y);
        Molecule v1 = environment.getMolecule(chainStart + 2, Y);
        assertThat(v0.type()).isEqualTo(Config.TYPE_DATA);
        assertThat(v1.type()).isEqualTo(Config.TYPE_DATA);

        // Decode values (two's complement in VALUE_MASK range)
        int val0 = decodeSignedValue(v0.value());
        int val1 = decodeSignedValue(v1.value());

        // Unit vector: exactly one component is +/-1, the other is 0
        int absSum = Math.abs(val0) + Math.abs(val1);
        assertThat(absSum).as("Unit vector should have exactly one +/-1 component").isEqualTo(1);
    }

    @Test
    void locationRegisterGeneratesLrBankValues() {
        createScanLine(LEFT, RIGHT, Y);

        // DPLR uses LOCATION_REGISTER operand
        Integer dplrId = Instruction.getInstructionIdByName("DPLR");
        assertThat(dplrId).isNotNull();
        List<OperandSource> sources = Instruction.getOperandSourcesById(dplrId);

        RegisterConfig lrConfig = new RegisterConfig(List.of(new int[]{RegisterBank.LR.base, 0, 3}));
        ArgumentConfig argConfig = new ArgumentConfig(null, lrConfig, null, null, null);
        InstructionEntry entry = new InstructionEntry(
                List.of(dplrId),
                List.of(sources),
                1.0,
                argConfig
        );

        for (int seed = 0; seed < 50; seed++) {
            clearNopGap(LEFT, RIGHT, Y);

            IRandomProvider rng = new SeededRandomProvider(seed);
            GeneInsertionPlugin plugin = new GeneInsertionPlugin(rng, 1.0, List.of(entry));
            plugin.mutate(child, environment);

            for (int x = LEFT + 1; x < RIGHT; x++) {
                Molecule mol = environment.getMolecule(x, Y);
                if (mol.type() == Config.TYPE_REGISTER) {
                    assertThat(mol.value())
                            .as("LR register value should be in [LR_BASE, LR_BASE+3] (seed=%d)", seed)
                            .isBetween(RegisterBank.LR.base, RegisterBank.LR.base + 3);
                }
            }
        }
    }

    @Test
    void chainLengthMatchesInstructionLength() {
        // ADDR has 2 REGISTER operands -> chain = [CODE, REGISTER, REGISTER] = 3
        createScanLine(LEFT, RIGHT, Y);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneInsertionPlugin plugin = new GeneInsertionPlugin(rng, 1.0, List.of(createAddrEntry()));
        plugin.mutate(child, environment);

        // Count non-empty cells in the NOP gap
        int nonEmpty = 0;
        for (int x = LEFT + 1; x < RIGHT; x++) {
            if (!environment.getMolecule(x, Y).isEmpty()) {
                nonEmpty++;
            }
        }
        // ADDR: CODE + REGISTER + REGISTER = 3
        assertThat(nonEmpty).as("Chain should be exactly 3 molecules (CODE + 2 REGISTER)").isEqualTo(3);
    }

    @Test
    void isStateless() {
        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneInsertionPlugin plugin = new GeneInsertionPlugin(rng, 0.03, List.of(createSetiEntry()));

        byte[] state = plugin.saveState();
        assertThat(state).isEmpty();

        // loadState should not throw
        plugin.loadState(new byte[0]);
    }

    @Test
    void labelRefGeneratesRandomHashWhenNoLabels() {
        // Only CODE molecules, no labels — LABELREF should use random hash
        createScanLine(LEFT, RIGHT, Y);

        Integer jmpiId = Instruction.getInstructionIdByName("JMPI");
        assertThat(jmpiId).isNotNull();
        List<OperandSource> sources = Instruction.getOperandSourcesById(jmpiId);

        ArgumentConfig argConfig = new ArgumentConfig(null, null, null, "existing", null);
        InstructionEntry entry = new InstructionEntry(
                List.of(jmpiId),
                List.of(sources),
                1.0,
                argConfig
        );

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneInsertionPlugin plugin = new GeneInsertionPlugin(rng, 1.0, List.of(entry));
        plugin.mutate(child, environment);

        // A LABELREF should be placed with a random hash
        boolean foundLabelRef = false;
        for (int x = LEFT + 1; x < RIGHT; x++) {
            Molecule mol = environment.getMolecule(x, Y);
            if (mol.type() == Config.TYPE_LABELREF) {
                assertThat(Molecule.extractTypedValue(mol.toInt()))
                        .as("LABELREF hash should lie within the value field")
                        .isBetween(0, Config.VALUE_MASK);
                foundLabelRef = true;
            }
        }
        assertThat(foundLabelRef).as("Should insert a LABELREF molecule even without existing labels").isTrue();
    }

    @Test
    void labelRefRandomHashCanSetTheTopValueBit() {
        // Only CODE molecules, no labels — LABELREF uses a random hash across the whole value field
        createScanLine(LEFT, RIGHT, Y);

        Integer jmpiId = Instruction.getInstructionIdByName("JMPI");
        assertThat(jmpiId).isNotNull();
        List<OperandSource> sources = Instruction.getOperandSourcesById(jmpiId);

        ArgumentConfig argConfig = new ArgumentConfig(null, null, null, "existing", null);
        InstructionEntry entry = new InstructionEntry(
                List.of(jmpiId),
                List.of(sources),
                1.0,
                argConfig
        );

        boolean foundTopBitSet = false;
        for (int seed = 0; seed < 50; seed++) {
            clearNopGap(LEFT, RIGHT, Y);

            IRandomProvider rng = new SeededRandomProvider(seed);
            GeneInsertionPlugin plugin = new GeneInsertionPlugin(rng, 1.0, List.of(entry));
            plugin.mutate(child, environment);

            for (int x = LEFT + 1; x < RIGHT; x++) {
                Molecule mol = environment.getMolecule(x, Y);
                if (mol.type() == Config.TYPE_LABELREF) {
                    int hash = Molecule.extractTypedValue(mol.toInt());
                    if ((hash & (1 << (Config.VALUE_BITS - 1))) != 0) {
                        foundTopBitSet = true;
                    }
                }
            }
        }
        assertThat(foundTopBitSet)
                .as("At least one invented LABELREF hash should set the value field's top bit")
                .isTrue();
    }

    @Test
    void insertsIntoInteriorNopAreaWhenWrapping() {
        // Organism wraps around the x=0/31 edge of the 32-wide world.
        // Owned cells at x=0, x=5, x=25, x=31 define a wrapping scan line at y=Y.
        // Shortest arc: x=25→26→...→31→0→...→5. Interior NOP: x=26..30 and x=1..4.
        // External space: x=6..24 (must remain empty).
        placeCode(0, Y);
        placeCode(5, Y);
        placeCode(25, Y);
        placeCode(31, Y);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneInsertionPlugin plugin = new GeneInsertionPlugin(rng, 1.0, List.of(createSetiEntry()));
        plugin.mutate(child, environment);

        // External space must remain empty
        for (int x = 6; x <= 24; x++) {
            assertThat(environment.getMolecule(x, Y).isEmpty())
                    .as("External cell (%d,%d) should remain empty", x, Y)
                    .isTrue();
        }

        // Chain should be placed somewhere in the interior NOP area
        boolean foundChain = false;
        for (int x : new int[]{26, 27, 28, 29, 30, 1, 2, 3, 4}) {
            if (!environment.getMolecule(x, Y).isEmpty()) {
                foundChain = true;
                break;
            }
        }
        assertThat(foundChain).as("Chain should be placed in interior NOP area").isTrue();
    }

    @Test
    void insertsIntoNopGapAtTheWorldEdgeOfASmallWrappingBody() {
        // A small body crossing the x=0/31 edge with its NOP gap exactly at the edge:
        // CODE at x=27,28,29 and x=2,3; the interior NOP run is x=30,31,0,1.
        // The raw span 2..29 is shorter than the axis, yet the shortest arc wraps.
        // External space: x=4..26 (must remain empty).
        placeCode(27, Y);
        placeCode(28, Y);
        placeCode(29, Y);
        placeCode(2, Y);
        placeCode(3, Y);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneInsertionPlugin plugin = new GeneInsertionPlugin(rng, 1.0, List.of(createSetiEntry()));
        plugin.mutate(child, environment);

        for (int x = 4; x <= 26; x++) {
            assertThat(environment.getMolecule(x, Y).isEmpty())
                    .as("External cell (%d,%d) should remain empty", x, Y)
                    .isTrue();
        }
        boolean foundChain = false;
        for (int x : new int[]{30, 31, 0, 1}) {
            if (!environment.getMolecule(x, Y).isEmpty()) {
                foundChain = true;
                break;
            }
        }
        assertThat(foundChain).as("Chain should be placed in the NOP gap at the world edge").isTrue();
    }

    @Test
    void negativeDvPlacesChainWithinNopArea() {
        // Child with DV=(-1, 0). Boundaries at x=10 and x=14, NOP area x=11,12,13.
        // Without the negative-DV fix, the chain would overwrite x=10 (the boundary).
        Organism negChild = Organism.restore(3, 9)
                .parentId(1)
                .ip(new int[]{0, 0})
                .dv(new int[]{-1, 0})
                .initialPosition(new int[]{0, 0})
                .energy(5000)
                .build(simulation);
        simulation.addOrganism(negChild);

        int negId = negChild.getId();
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 42), negId, new int[]{10, Y});
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 42), negId, new int[]{14, Y});

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneInsertionPlugin plugin = new GeneInsertionPlugin(rng, 1.0, List.of(createSetiEntry()));
        plugin.mutate(negChild, environment);

        // Boundaries must be preserved
        assertThat(environment.getMolecule(10, Y).value())
                .as("Left boundary should be preserved").isEqualTo(42);
        assertThat(environment.getMolecule(14, Y).value())
                .as("Right boundary should be preserved").isEqualTo(42);

        // Chain should be placed within the NOP area (x=11..13)
        boolean foundChain = false;
        for (int x = 11; x <= 13; x++) {
            if (!environment.getMolecule(x, Y).isEmpty()) {
                foundChain = true;
                break;
            }
        }
        assertThat(foundChain).as("Chain should be placed in NOP area").isTrue();

        // Nothing outside scan line extent
        for (int x = 0; x < 10; x++) {
            assertThat(environment.getMolecule(x, Y).isEmpty())
                    .as("Cell (%d,%d) outside scan line should be empty", x, Y).isTrue();
        }
        for (int x = 15; x < 30; x++) {
            assertThat(environment.getMolecule(x, Y).isEmpty())
                    .as("Cell (%d,%d) outside scan line should be empty", x, Y).isTrue();
        }
    }

    // ---- Mutation record tests ----

    /** The flat index the environment persists the cell at the given coordinate by. */
    private int flatIndex(int x, int y) {
        return environment.getProperties().toFlatIndex(new int[]{x, y});
    }

    @Test
    void aPlacedInstructionChainIsRecordedInPlacementOrder() {
        // The NOP gap between the two boundary cells is the only run, so the chain of three
        // molecules starts right behind the left boundary
        createScanLine(LEFT, RIGHT, Y);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneInsertionPlugin plugin = new GeneInsertionPlugin(rng, 1.0, List.of(createSetiEntry()));
        plugin.mutate(child, environment);

        List<MutationRecord> records = child.getBirthMutations();
        assertThat(records).hasSize(1);
        MutationRecord record = records.get(0);
        assertThat(record.pluginClass()).isEqualTo(GeneInsertionPlugin.class.getName());
        assertThat(record.kind()).isEqualTo("instruction-insertion");
        assertThat(record.cells()).containsExactly(
                flatIndex(LEFT + 1, Y), flatIndex(LEFT + 2, Y), flatIndex(LEFT + 3, Y));
        assertThat(record.oldValues()).containsExactly(0, 0, 0);
        assertThat(record.newValues()).containsExactly(
                environment.getMolecule(LEFT + 1, Y).toInt(),
                environment.getMolecule(LEFT + 2, Y).toInt(),
                environment.getMolecule(LEFT + 3, Y).toInt());
        assertThat(record.params()).isEmpty();
        assertThat(record.dv()).isEqualTo(child.getDv());
    }

    @Test
    void aRunWithoutANopAreaRecordsNothing() {
        for (int x = 0; x < 25; x++) {
            placeCode(x, Y);
        }

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneInsertionPlugin plugin = new GeneInsertionPlugin(rng, 1.0, List.of(createSetiEntry()));
        plugin.mutate(child, environment);

        assertThat(child.getBirthMutations()).isNull();
    }

    /**
     * Decodes a VALUE_MASK-encoded signed value (two's complement).
     */
    private static int decodeSignedValue(int encoded) {
        if (encoded > (Config.VALUE_MASK >> 1)) {
            return encoded - (Config.VALUE_MASK + 1);
        }
        return encoded;
    }
}
