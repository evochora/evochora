package org.evochora.runtime.worldgen;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.OpcodeId;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GeneSubstitutionPlugin}.
 * <p>
 * Tests use a 30x20 toroidal environment with a child organism owning molecules
 * of various types. Each test verifies that the type-specific mutation strategy
 * produces correct results within the expected constraints.
 */
@Tag("unit")
class GeneSubstitutionPluginTest {

    private Environment environment;
    private Organism child;

    /** ADDR opcode: ARITHMETIC, operation=0, variant=RR (two registers). */
    private static int ADDR_OPCODE;
/** NOP opcode: SPECIAL, operation=0, variant=NONE (no operands). */
    private static int NOP_OPCODE;

    @BeforeAll
    static void initInstructions() {
        Instruction.init();
        ADDR_OPCODE = Instruction.getInstructionIdByName("ADDR");
        NOP_OPCODE = Instruction.getInstructionIdByName("NOP");
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

        Simulation simulation = new Simulation(environment, policyManager, organismConfig, 1);

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

    // ---- Helper methods ----

    private void placeCode(int x, int y, int opcodeValue) {
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcodeValue), child.getId(), new int[]{x, y});
    }

    private void placeRegister(int x, int y, int regId) {
        environment.setMolecule(new Molecule(Config.TYPE_REGISTER, regId), child.getId(), new int[]{x, y});
    }

    private void placeData(int x, int y, int value) {
        environment.setMolecule(new Molecule(Config.TYPE_DATA, value), child.getId(), new int[]{x, y});
    }

    private void placeLabel(int x, int y, int hash) {
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, hash), child.getId(), new int[]{x, y});
    }

    private void placeLabelref(int x, int y, int hash) {
        environment.setMolecule(new Molecule(Config.TYPE_LABELREF, hash), child.getId(), new int[]{x, y});
    }

    private void placeEnergy(int x, int y, int value) {
        environment.setMolecule(new Molecule(Config.TYPE_ENERGY, value), child.getId(), new int[]{x, y});
    }

    private void placeStructure(int x, int y, int value) {
        environment.setMolecule(new Molecule(Config.TYPE_STRUCTURE, value), child.getId(), new int[]{x, y});
    }

    /** Creates a plugin that only mutates CODE molecules. */
    private GeneSubstitutionPlugin codeOnlyPlugin(IRandomProvider rng) {
        return new GeneSubstitutionPlugin(rng, 1.0,
                1.0, 0.0, 0.0, 0.0, 0.0,
                0.7, 0.2, 0.1,
                0.5, 1, 1);
    }

    /** Creates a plugin that only mutates REGISTER molecules. */
    private GeneSubstitutionPlugin registerOnlyPlugin(IRandomProvider rng) {
        return new GeneSubstitutionPlugin(rng, 1.0,
                0.0, 1.0, 0.0, 0.0, 0.0,
                0.7, 0.2, 0.1,
                0.5, 1, 1);
    }

    /** Creates a plugin that only mutates DATA molecules. */
    private GeneSubstitutionPlugin dataOnlyPlugin(IRandomProvider rng) {
        return new GeneSubstitutionPlugin(rng, 1.0,
                0.0, 0.0, 1.0, 0.0, 0.0,
                0.7, 0.2, 0.1,
                0.5, 1, 1);
    }

    /** Creates a plugin that only mutates LABEL molecules. */
    private GeneSubstitutionPlugin labelOnlyPlugin(IRandomProvider rng) {
        return new GeneSubstitutionPlugin(rng, 1.0,
                0.0, 0.0, 0.0, 1.0, 0.0,
                0.7, 0.2, 0.1,
                0.5, 1, 1);
    }

    /** Creates a plugin that only mutates LABELREF molecules. */
    private GeneSubstitutionPlugin labelrefOnlyPlugin(IRandomProvider rng) {
        return new GeneSubstitutionPlugin(rng, 1.0,
                0.0, 0.0, 0.0, 0.0, 1.0,
                0.7, 0.2, 0.1,
                0.5, 1, 1);
    }

    /** Creates a plugin that mutates all types equally. */
    private GeneSubstitutionPlugin allTypesPlugin(IRandomProvider rng) {
        return new GeneSubstitutionPlugin(rng, 1.0,
                1.0, 1.0, 1.0, 1.0, 1.0,
                0.7, 0.2, 0.1,
                0.5, 1, 1);
    }

    // ---- CODE mutation tests ----

    @Test
    void mutatesCodeToValidOpcode() {
        int mutated = 0;
        for (int seed = 0; seed < 100; seed++) {
            setUp();
            placeCode(5, 5, ADDR_OPCODE);
            GeneSubstitutionPlugin plugin = codeOnlyPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);

            Molecule mol = environment.getMolecule(5, 5);
            int newValue = mol.value();
            if (newValue != ADDR_OPCODE) {
                mutated++;
                assertThat(Instruction.getInstructionClassById(newValue | Config.TYPE_CODE))
                        .as("Mutated opcode %d (seed=%d) must be registered", newValue, seed)
                        .isNotNull();
            }
        }
        assertThat(mutated).as("At least some mutations should occur across 100 seeds").isGreaterThan(0);
    }

    @Test
    void operationFlipPreservesFamilyAndVariant() {
        int verified = 0;
        for (int seed = 0; seed < 200; seed++) {
            setUp();
            placeCode(5, 5, ADDR_OPCODE);
            // Only operation flip
            GeneSubstitutionPlugin plugin = new GeneSubstitutionPlugin(
                    new SeededRandomProvider(seed), 1.0,
                    1.0, 0.0, 0.0, 0.0, 0.0,
                    1.0, 0.0, 0.0,  // only operation flip
                    0.5, 1, 1);
            plugin.substitute(child, environment);

            int newValue = environment.getMolecule(5, 5).value();
            if (newValue != ADDR_OPCODE) {
                int origFamily = OpcodeId.extractFamily(ADDR_OPCODE);
                int origVariant = OpcodeId.extractVariant(ADDR_OPCODE);
                assertThat(OpcodeId.extractFamily(newValue)).isEqualTo(origFamily);
                assertThat(OpcodeId.extractVariant(newValue)).isEqualTo(origVariant);
                assertThat(OpcodeId.extractOperation(newValue)).isNotEqualTo(OpcodeId.extractOperation(ADDR_OPCODE));
                verified++;
            }
        }
        assertThat(verified).as("Should verify at least some operation flips").isGreaterThan(0);
    }

    @Test
    void familyFlipPreservesOperationAndVariant() {
        int verified = 0;
        for (int seed = 0; seed < 200; seed++) {
            setUp();
            placeCode(5, 5, ADDR_OPCODE);
            // Only family flip
            GeneSubstitutionPlugin plugin = new GeneSubstitutionPlugin(
                    new SeededRandomProvider(seed), 1.0,
                    1.0, 0.0, 0.0, 0.0, 0.0,
                    0.0, 1.0, 0.0,  // only family flip
                    0.5, 1, 1);
            plugin.substitute(child, environment);

            int newValue = environment.getMolecule(5, 5).value();
            if (newValue != ADDR_OPCODE) {
                int origOperation = OpcodeId.extractOperation(ADDR_OPCODE);
                int origVariant = OpcodeId.extractVariant(ADDR_OPCODE);
                assertThat(OpcodeId.extractOperation(newValue)).isEqualTo(origOperation);
                assertThat(OpcodeId.extractVariant(newValue)).isEqualTo(origVariant);
                assertThat(OpcodeId.extractFamily(newValue)).isNotEqualTo(OpcodeId.extractFamily(ADDR_OPCODE));
                verified++;
            }
        }
        assertThat(verified).as("Should verify at least some family flips").isGreaterThan(0);
    }

    @Test
    void variantFlipStaysInArityGroup() {
        int verified = 0;
        for (int seed = 0; seed < 200; seed++) {
            setUp();
            placeCode(5, 5, ADDR_OPCODE);
            // Only variant flip
            GeneSubstitutionPlugin plugin = new GeneSubstitutionPlugin(
                    new SeededRandomProvider(seed), 1.0,
                    1.0, 0.0, 0.0, 0.0, 0.0,
                    0.0, 0.0, 1.0,  // only variant flip
                    0.5, 1, 1);
            plugin.substitute(child, environment);

            int newValue = environment.getMolecule(5, 5).value();
            if (newValue != ADDR_OPCODE) {
                int origFamily = OpcodeId.extractFamily(ADDR_OPCODE);
                int origOperation = OpcodeId.extractOperation(ADDR_OPCODE);
                int origVariant = OpcodeId.extractVariant(ADDR_OPCODE);
                assertThat(OpcodeId.extractFamily(newValue)).isEqualTo(origFamily);
                assertThat(OpcodeId.extractOperation(newValue)).isEqualTo(origOperation);
                int newVariant = OpcodeId.extractVariant(newValue);
                assertThat(GeneSubstitutionPlugin.arityGroup(newVariant))
                        .as("New variant %d must be in same arity group as %d", newVariant, origVariant)
                        .isEqualTo(GeneSubstitutionPlugin.arityGroup(origVariant));
                verified++;
            }
        }
        assertThat(verified).as("Should verify at least some variant flips").isGreaterThan(0);
    }

    @Test
    void codeSkipsWhenNoAlternatives() {
        // NOP is unique: SPECIAL family, operation 0, variant NONE (0-arg group).
        // No other opcode shares any of its components in a useful way.
        placeCode(5, 5, NOP_OPCODE);
        GeneSubstitutionPlugin plugin = codeOnlyPlugin(new SeededRandomProvider(42));
        plugin.substitute(child, environment);

        // NOP value is 0, which is also empty — so the plugin should skip it entirely
        // because moleculeInt == 0 is skipped in reservoir sampling.
        // The molecule should remain unchanged.
        assertThat(environment.getMolecule(5, 5).value()).isEqualTo(NOP_OPCODE);
    }

    // ---- REGISTER mutation tests ----

    @Test
    void registerStaysInDrBank() {
        for (int seed = 0; seed < 50; seed++) {
            setUp();
            placeRegister(5, 5, 3); // DR3
            GeneSubstitutionPlugin plugin = registerOnlyPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);

            int newValue = environment.getMolecule(5, 5).value();
            assertThat(newValue).as("seed=%d", seed).isBetween(0, Config.NUM_DATA_REGISTERS - 1);
        }
    }

    @Test
    void registerStaysInPrBank() {
        for (int seed = 0; seed < 50; seed++) {
            setUp();
            placeRegister(5, 5, Instruction.PR_BASE + 3); // PR3
            GeneSubstitutionPlugin plugin = registerOnlyPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);

            int newValue = environment.getMolecule(5, 5).value();
            assertThat(newValue).as("seed=%d", seed)
                    .isBetween(Instruction.PR_BASE, Instruction.PR_BASE + Config.NUM_PROC_REGISTERS - 1);
        }
    }

    @Test
    void registerStaysInFprBank() {
        for (int seed = 0; seed < 50; seed++) {
            setUp();
            placeRegister(5, 5, Instruction.FPR_BASE + 3); // FPR3
            GeneSubstitutionPlugin plugin = registerOnlyPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);

            int newValue = environment.getMolecule(5, 5).value();
            assertThat(newValue).as("seed=%d", seed)
                    .isBetween(Instruction.FPR_BASE, Instruction.FPR_BASE + Config.NUM_FORMAL_PARAM_REGISTERS - 1);
        }
    }

    @Test
    void registerStaysInLrBank() {
        for (int seed = 0; seed < 50; seed++) {
            setUp();
            placeRegister(5, 5, Instruction.LR_BASE + 1); // LR1
            GeneSubstitutionPlugin plugin = registerOnlyPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);

            int newValue = environment.getMolecule(5, 5).value();
            assertThat(newValue).as("seed=%d", seed)
                    .isBetween(Instruction.LR_BASE, Instruction.LR_BASE + Config.NUM_LOCATION_REGISTERS - 1);
        }
    }

    @Test
    void registerClampsAtBoundary() {
        boolean sawClampLow = false;
        boolean sawClampHigh = false;
        for (int seed = 0; seed < 100; seed++) {
            setUp();
            placeRegister(5, 5, 0); // DR0 — can only go to 0 or 1
            GeneSubstitutionPlugin plugin = registerOnlyPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);
            int newLow = environment.getMolecule(5, 5).value();
            assertThat(newLow).isBetween(0, 1);
            if (newLow == 0) sawClampLow = true;

            setUp();
            placeRegister(5, 5, Config.NUM_DATA_REGISTERS - 1); // DR7 — can only go to 6 or 7
            plugin = registerOnlyPlugin(new SeededRandomProvider(seed + 1000));
            plugin.substitute(child, environment);
            int newHigh = environment.getMolecule(5, 5).value();
            assertThat(newHigh).isBetween(Config.NUM_DATA_REGISTERS - 2, Config.NUM_DATA_REGISTERS - 1);
            if (newHigh == Config.NUM_DATA_REGISTERS - 1) sawClampHigh = true;
        }
        assertThat(sawClampLow).as("Should see DR0 clamped at boundary").isTrue();
        assertThat(sawClampHigh).as("Should see DR7 clamped at boundary").isTrue();
    }

    // ---- DATA mutation tests ----

    @Test
    void dataScaleProportionalDelta() {
        // For value=100, exponent=0.5: delta=max(1, round(sqrt(100)))=10
        // So results should be in [90, 110]
        for (int seed = 0; seed < 50; seed++) {
            setUp();
            placeData(5, 5, 100);
            GeneSubstitutionPlugin plugin = dataOnlyPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);

            int newValue = environment.getMolecule(5, 5).value();
            assertThat(newValue).as("seed=%d", seed).isBetween(90, 110);
        }
    }

    @Test
    void dataClampedToValidRange() {
        // Test near zero: value=1, delta=max(1,round(1^0.5))=1, range=[0,2]
        for (int seed = 0; seed < 50; seed++) {
            setUp();
            placeData(5, 5, 1);
            GeneSubstitutionPlugin plugin = dataOnlyPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);

            int newValue = environment.getMolecule(5, 5).value();
            assertThat(newValue).as("seed=%d: value=1 should stay in [0,2]", seed)
                    .isBetween(0, 2);
        }

        // Test at zero: value=0, delta=max(1,round(0^0.5))=1, range=[-1,1] clamped to [0,1]
        for (int seed = 0; seed < 50; seed++) {
            setUp();
            placeData(5, 5, 0);
            GeneSubstitutionPlugin plugin = dataOnlyPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);

            int newValue = environment.getMolecule(5, 5).value();
            assertThat(newValue).as("seed=%d: value=0 should stay in [0,1]", seed)
                    .isBetween(0, 1);
        }
    }

    // ---- LABEL / LABELREF mutation tests ----

    @Test
    void labelFlipsBits() {
        int originalHash = 0b1010101010101010101; // 19-bit value
        int verified = 0;
        for (int seed = 0; seed < 50; seed++) {
            setUp();
            placeLabel(5, 5, originalHash);
            GeneSubstitutionPlugin plugin = labelOnlyPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);

            int newHash = environment.getMolecule(5, 5).value();
            if (newHash != originalHash) {
                int diff = newHash ^ originalHash;
                assertThat(Integer.bitCount(diff))
                        .as("seed=%d: Hamming distance should be exactly 1", seed)
                        .isEqualTo(1);
                verified++;
            }
        }
        assertThat(verified).as("Should verify at least some label bit-flips").isGreaterThan(0);
    }

    @Test
    void labelrefFlipsBits() {
        int originalHash = 0b0101010101010101010;
        int verified = 0;
        for (int seed = 0; seed < 50; seed++) {
            setUp();
            placeLabelref(5, 5, originalHash);
            GeneSubstitutionPlugin plugin = labelrefOnlyPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);

            int newHash = environment.getMolecule(5, 5).value();
            if (newHash != originalHash) {
                int diff = newHash ^ originalHash;
                assertThat(Integer.bitCount(diff))
                        .as("seed=%d: Hamming distance should be exactly 1", seed)
                        .isEqualTo(1);
                verified++;
            }
        }
        assertThat(verified).as("Should verify at least some labelref bit-flips").isGreaterThan(0);
    }

    // ---- Type exclusion tests ----

    @Test
    void neverMutatesEnergy() {
        int originalValue = 500;
        for (int seed = 0; seed < 50; seed++) {
            setUp();
            placeEnergy(5, 5, originalValue);
            GeneSubstitutionPlugin plugin = allTypesPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);

            assertThat(environment.getMolecule(5, 5).value())
                    .as("seed=%d: ENERGY should never be mutated", seed)
                    .isEqualTo(originalValue);
        }
    }

    @Test
    void neverMutatesStructure() {
        int originalValue = 100;
        for (int seed = 0; seed < 50; seed++) {
            setUp();
            placeStructure(5, 5, originalValue);
            GeneSubstitutionPlugin plugin = allTypesPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);

            assertThat(environment.getMolecule(5, 5).value())
                    .as("seed=%d: STRUCTURE should never be mutated", seed)
                    .isEqualTo(originalValue);
        }
    }

    // ---- Weight system tests ----

    @Test
    void weightZeroDisablesType() {
        // codeWeight=0, registerWeight=1 → should only ever mutate REGISTER
        for (int seed = 0; seed < 50; seed++) {
            setUp();
            placeCode(5, 5, ADDR_OPCODE);
            placeRegister(10, 5, 3);

            GeneSubstitutionPlugin plugin = new GeneSubstitutionPlugin(
                    new SeededRandomProvider(seed), 1.0,
                    0.0, 1.0, 0.0, 0.0, 0.0,  // only REGISTER enabled
                    0.7, 0.2, 0.1,
                    0.5, 1, 1);
            plugin.substitute(child, environment);

            assertThat(environment.getMolecule(5, 5).value())
                    .as("seed=%d: CODE with weight=0 should not be mutated", seed)
                    .isEqualTo(ADDR_OPCODE);
        }
    }

    @Test
    void weightedSelectionRespectsWeights() {
        int codeHits = 0;
        int regHits = 0;
        for (int seed = 0; seed < 500; seed++) {
            setUp();
            placeCode(5, 5, ADDR_OPCODE);
            placeRegister(10, 5, 3);

            GeneSubstitutionPlugin plugin = new GeneSubstitutionPlugin(
                    new SeededRandomProvider(seed), 1.0,
                    10.0, 1.0, 0.0, 0.0, 0.0,  // CODE weight 10x REGISTER
                    0.7, 0.2, 0.1,
                    0.5, 1, 1);
            plugin.substitute(child, environment);

            if (environment.getMolecule(5, 5).value() != ADDR_OPCODE) {
                codeHits++;
            }
            if (environment.getMolecule(10, 5).value() != 3) {
                regHits++;
            }
        }
        // With 10:1 weight ratio and equal molecule counts, expect roughly 10:1 hit ratio.
        // Allow generous margin for randomness.
        assertThat(codeHits).as("CODE (weight=10) should be hit much more than REGISTER (weight=1)")
                .isGreaterThan(regHits * 2);
    }

    // ---- Edge case tests ----

    @Test
    void zeroRateNeverMutates() {
        placeCode(5, 5, ADDR_OPCODE);
        placeData(10, 5, 100);

        GeneSubstitutionPlugin plugin = new GeneSubstitutionPlugin(
                new SeededRandomProvider(42), 0.0,  // rate = 0
                1.0, 1.0, 1.0, 1.0, 1.0,
                0.7, 0.2, 0.1,
                0.5, 1, 1);

        for (int i = 0; i < 100; i++) {
            plugin.onBirth(child, environment);
        }

        assertThat(environment.getMolecule(5, 5).value()).isEqualTo(ADDR_OPCODE);
        assertThat(environment.getMolecule(10, 5).value()).isEqualTo(100);
    }

    @Test
    void skipsWhenNoOwnedCells() {
        // Child has no owned cells at all
        GeneSubstitutionPlugin plugin = allTypesPlugin(new SeededRandomProvider(42));
        plugin.substitute(child, environment); // should not throw
    }

    @Test
    void skipsWhenOnlyEmptyCells() {
        // Place CODE:0 (empty) molecules — these are skipped by the reservoir sampling
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 0), child.getId(), new int[]{5, 5});
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 0), child.getId(), new int[]{6, 5});

        GeneSubstitutionPlugin plugin = allTypesPlugin(new SeededRandomProvider(42));
        plugin.substitute(child, environment); // should not throw

        assertThat(environment.getMolecule(5, 5).isEmpty()).isTrue();
        assertThat(environment.getMolecule(6, 5).isEmpty()).isTrue();
    }

    @Test
    void preservesMarkerBits() {
        // Place a DATA molecule with marker=5
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 100, 5), child.getId(), new int[]{5, 5});

        boolean mutated = false;
        for (int seed = 0; seed < 50; seed++) {
            setUp();
            environment.setMolecule(new Molecule(Config.TYPE_DATA, 100, 5), child.getId(), new int[]{5, 5});
            GeneSubstitutionPlugin plugin = dataOnlyPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);

            Molecule mol = environment.getMolecule(5, 5);
            assertThat(mol.marker()).as("seed=%d: marker should be preserved", seed).isEqualTo(5);
            if (mol.value() != 100) {
                mutated = true;
            }
        }
        assertThat(mutated).as("Should mutate value while preserving marker").isTrue();
    }

    // ---- Plugin contract tests ----

    @Test
    void isStateless() {
        GeneSubstitutionPlugin plugin = allTypesPlugin(new SeededRandomProvider(42));
        assertThat(plugin.saveState()).isEmpty();
        plugin.loadState(new byte[0]); // should not throw
    }

    @Test
    void flipBitsProducesExactDifference() {
        int original = 0b1010101010101010101;

        for (int seed = 0; seed < 50; seed++) {
            GeneSubstitutionPlugin p = allTypesPlugin(new SeededRandomProvider(seed));
            int flipped = p.flipBits(original, 3);
            int diff = flipped ^ original;
            assertThat(Integer.bitCount(diff))
                    .as("seed=%d: flipping 3 bits should produce exactly 3 bit difference", seed)
                    .isEqualTo(3);
            assertThat(flipped).as("seed=%d: result must be within 19-bit range", seed)
                    .isBetween(0, (1 << 19) - 1);
        }
    }
}
