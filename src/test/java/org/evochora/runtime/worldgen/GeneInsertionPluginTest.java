package org.evochora.runtime.worldgen;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.Instruction.OperandSource;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;
import org.evochora.runtime.worldgen.PointMutationPlugin.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PointMutationPlugin}.
 * <p>
 * Tests use realistic genome layouts: boundary CODE molecules owned by the child
 * define scan line extent, with naturally empty (moleculeInt == 0, owner == 0) cells
 * between them providing NOP areas for mutation insertion.
 */
@Tag("unit")
class PointMutationPluginTest {

    private Simulation simulation;
    private Environment environment;
    private Organism child;

    private static final int LABEL_HASH_A = 11111;

    /** Left boundary x-coordinate for the standard scan line. */
    private static final int LEFT = 2;
    /** Right boundary x-coordinate for the standard scan line. */
    private static final int RIGHT = 20;
    /** Y-coordinate for the standard scan line. */
    private static final int Y = 5;

    @BeforeAll
    static void initInstructions() {
        Instruction.init();
    }

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

        simulation = new Simulation(environment, policyManager, organismConfig);

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
        PointMutationPlugin plugin = new PointMutationPlugin(rng, 1.0, List.of(createSetiEntry()));
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
        PointMutationPlugin plugin = new PointMutationPlugin(rng, 1.0, List.of(createSetiEntry()));
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
            PointMutationPlugin plugin = new PointMutationPlugin(rng, 1.0, List.of(createAddrEntry()));
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
            PointMutationPlugin plugin = new PointMutationPlugin(rng, 1.0, List.of(entry));
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
        PointMutationPlugin plugin = new PointMutationPlugin(rng, 1.0, List.of(entry));
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

    @Test
    void labelEntryFlipsBits() {
        // Place a label as the left boundary
        placeLabel(LEFT, Y, LABEL_HASH_A);
        placeCode(RIGHT, Y);

        LabelEntry labelEntry = new LabelEntry(1.0, 2);

        IRandomProvider rng = new SeededRandomProvider(42L);
        PointMutationPlugin plugin = new PointMutationPlugin(rng, 1.0, List.of(labelEntry));
        plugin.mutate(child, environment);

        // Find inserted LABEL molecule in the NOP gap
        boolean foundLabel = false;
        for (int x = LEFT + 1; x < RIGHT; x++) {
            Molecule mol = environment.getMolecule(x, Y);
            if (mol.type() == Config.TYPE_LABEL) {
                int newHash = mol.value();
                // Should differ by exactly 2 bits from LABEL_HASH_A
                int diff = newHash ^ LABEL_HASH_A;
                assertThat(Integer.bitCount(diff)).as("Hash should differ by exactly 2 bits").isEqualTo(2);
                foundLabel = true;
            }
        }
        assertThat(foundLabel).as("Should insert a LABEL molecule").isTrue();
    }

    @Test
    void labelEntryGeneratesRandomHashWhenNoLabels() {
        // Only CODE molecules, no labels in the genome
        createScanLine(LEFT, RIGHT, Y);

        LabelEntry labelEntry = new LabelEntry(1.0, 2);

        IRandomProvider rng = new SeededRandomProvider(42L);
        PointMutationPlugin plugin = new PointMutationPlugin(rng, 1.0, List.of(labelEntry));
        plugin.mutate(child, environment);

        // A label should be placed despite no existing labels (uses random hash)
        boolean foundLabel = false;
        for (int x = LEFT + 1; x < RIGHT; x++) {
            Molecule mol = environment.getMolecule(x, Y);
            if (mol.type() == Config.TYPE_LABEL) {
                assertThat(mol.value()).as("Label hash should be in valid 19-bit range")
                        .isBetween(0, (1 << 19) - 1);
                foundLabel = true;
            }
        }
        assertThat(foundLabel).as("Should insert a LABEL molecule even without existing labels").isTrue();
    }

    @Test
    void skipsWhenNoNopArea() {
        // Fill entire scan line with non-empty code (no NOP gaps)
        for (int x = 0; x < 25; x++) {
            placeCode(x, Y);
        }

        IRandomProvider rng = new SeededRandomProvider(42L);
        PointMutationPlugin plugin = new PointMutationPlugin(rng, 1.0, List.of(createSetiEntry()));
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
        PointMutationPlugin plugin = new PointMutationPlugin(rng, 0.0, List.of(createSetiEntry()));
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
        PointMutationPlugin plugin = new PointMutationPlugin(rng, 1.0, List.of(entry));
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

        RegisterConfig lrConfig = new RegisterConfig(List.of(new int[]{Instruction.LR_BASE, 0, 3}));
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
            PointMutationPlugin plugin = new PointMutationPlugin(rng, 1.0, List.of(entry));
            plugin.mutate(child, environment);

            for (int x = LEFT + 1; x < RIGHT; x++) {
                Molecule mol = environment.getMolecule(x, Y);
                if (mol.type() == Config.TYPE_REGISTER) {
                    assertThat(mol.value())
                            .as("LR register value should be in [LR_BASE, LR_BASE+3] (seed=%d)", seed)
                            .isBetween(Instruction.LR_BASE, Instruction.LR_BASE + 3);
                }
            }
        }
    }

    @Test
    void chainLengthMatchesInstructionLength() {
        // ADDR has 2 REGISTER operands -> chain = [CODE, REGISTER, REGISTER] = 3
        createScanLine(LEFT, RIGHT, Y);

        IRandomProvider rng = new SeededRandomProvider(42L);
        PointMutationPlugin plugin = new PointMutationPlugin(rng, 1.0, List.of(createAddrEntry()));
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
        PointMutationPlugin plugin = new PointMutationPlugin(rng, 0.03, List.of(createSetiEntry()));

        byte[] state = plugin.saveState();
        assertThat(state).isEmpty();

        // loadState should not throw
        plugin.loadState(new byte[0]);
    }

    @Test
    void flipBitsProducesExactDifference() {
        int originalHash = 0b1010101010101010101; // 19-bit value
        for (int flips = 0; flips <= 5; flips++) {
            IRandomProvider testRng = new SeededRandomProvider(flips * 17L);
            PointMutationPlugin testPlugin = new PointMutationPlugin(testRng, 1.0, List.of(
                    new LabelEntry(1.0, 0)
            ));
            int result = testPlugin.flipBits(originalHash, flips);
            int diff = result ^ originalHash;
            assertThat(Integer.bitCount(diff)).as("flipBits(%d) should flip exactly %d bits", flips, flips)
                    .isEqualTo(flips);
        }
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
        PointMutationPlugin plugin = new PointMutationPlugin(rng, 1.0, List.of(entry));
        plugin.mutate(child, environment);

        // A LABELREF should be placed with a random hash
        boolean foundLabelRef = false;
        for (int x = LEFT + 1; x < RIGHT; x++) {
            Molecule mol = environment.getMolecule(x, Y);
            if (mol.type() == Config.TYPE_LABELREF) {
                assertThat(mol.value()).as("LABELREF hash should be in valid 19-bit range")
                        .isBetween(0, (1 << 19) - 1);
                foundLabelRef = true;
            }
        }
        assertThat(foundLabelRef).as("Should insert a LABELREF molecule even without existing labels").isTrue();
    }

    @Test
    void insertsIntoInteriorNopAreaWhenWrapping() {
        // Organism wraps around x=0/29 boundary in the 30-wide world.
        // Owned cells at x=0, x=5, x=25, x=29 define a wrapping scan line at y=Y.
        // Shortest arc: x=25→26→...→29→0→...→5. Interior NOP: x=26,27,28 and x=1,2,3,4.
        // External space: x=6..24 (must remain empty).
        placeCode(0, Y);
        placeCode(5, Y);
        placeCode(25, Y);
        placeCode(29, Y);

        IRandomProvider rng = new SeededRandomProvider(42L);
        PointMutationPlugin plugin = new PointMutationPlugin(rng, 1.0, List.of(createSetiEntry()));
        plugin.mutate(child, environment);

        // External space must remain empty
        for (int x = 6; x <= 24; x++) {
            assertThat(environment.getMolecule(x, Y).isEmpty())
                    .as("External cell (%d,%d) should remain empty", x, Y)
                    .isTrue();
        }

        // Chain should be placed somewhere in the interior NOP area
        boolean foundChain = false;
        for (int x : new int[]{26, 27, 28, 1, 2, 3, 4}) {
            if (!environment.getMolecule(x, Y).isEmpty()) {
                foundChain = true;
                break;
            }
        }
        assertThat(foundChain).as("Chain should be placed in interior NOP area").isTrue();
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
        PointMutationPlugin plugin = new PointMutationPlugin(rng, 1.0, List.of(createSetiEntry()));
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
