package org.evochora.runtime.worldgen;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.OpcodeId;
import org.evochora.runtime.isa.RegisterBank;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.MutationRecord;
import org.evochora.runtime.model.MoleculeTypeRegistry;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.typesafe.config.ConfigFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link GeneSubstitutionPlugin}.
 * <p>
 * Tests use a 32x32 toroidal environment with a child organism owning molecules
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

    /**
     * A hard comparison and its probabilistic twin are conditionals of the same variant, so one
     * operation flip turns the one into the other.
     */
    @Test
    void operationFlipOfAComparisonReachesItsProbabilisticTwin() {
        int gti = Instruction.getInstructionIdByName("GTI");
        Set<Integer> reached = new HashSet<>();
        for (int seed = 0; seed < 200; seed++) {
            setUp();
            placeCode(5, 5, gti);
            GeneSubstitutionPlugin plugin = new GeneSubstitutionPlugin(
                    new SeededRandomProvider(seed), 1.0,
                    1.0, 0.0, 0.0, 0.0, 0.0,
                    1.0, 0.0, 0.0,  // only operation flip
                    0.5, 1, 1);
            plugin.substitute(child, environment);

            reached.add(environment.getMolecule(5, 5).value());
        }

        assertThat(reached)
                .as("opcodes an operation flip of GTI reaches")
                .contains(Instruction.getInstructionIdByName("PGTI"));
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
    void registerStaysInPdrBank() {
        for (int seed = 0; seed < 50; seed++) {
            setUp();
            placeRegister(5, 5, RegisterBank.PDR.base + 3); // PDR3
            GeneSubstitutionPlugin plugin = registerOnlyPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);

            int newValue = environment.getMolecule(5, 5).value();
            assertThat(newValue).as("seed=%d", seed)
                    .isBetween(RegisterBank.PDR.base, RegisterBank.PDR.base + Config.NUM_PDR_REGISTERS - 1);
        }
    }

    @Test
    void registerStaysInFdrBank() {
        for (int seed = 0; seed < 50; seed++) {
            setUp();
            placeRegister(5, 5, RegisterBank.FDR.base + 3); // FDR3
            GeneSubstitutionPlugin plugin = registerOnlyPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);

            int newValue = environment.getMolecule(5, 5).value();
            assertThat(newValue).as("seed=%d", seed)
                    .isBetween(RegisterBank.FDR.base, RegisterBank.FDR.base + Config.NUM_FDR_REGISTERS - 1);
        }
    }

    @Test
    void registerStaysInLrBank() {
        for (int seed = 0; seed < 50; seed++) {
            setUp();
            placeRegister(5, 5, RegisterBank.LR.base + 1); // LR1
            GeneSubstitutionPlugin plugin = registerOnlyPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);

            int newValue = environment.getMolecule(5, 5).value();
            assertThat(newValue).as("seed=%d", seed)
                    .isBetween(RegisterBank.LR.base, RegisterBank.LR.base + Config.NUM_LOCATION_REGISTERS - 1);
        }
    }

    @Test
    void registerStaysInPlrBank() {
        for (int seed = 0; seed < 50; seed++) {
            setUp();
            placeRegister(5, 5, RegisterBank.PLR.base + 1); // PLR1
            GeneSubstitutionPlugin plugin = registerOnlyPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);

            int newValue = environment.getMolecule(5, 5).value();
            assertThat(newValue).as("seed=%d", seed)
                    .isBetween(RegisterBank.PLR.base, RegisterBank.PLR.base + Config.NUM_PLR_REGISTERS - 1);
        }
    }

    @Test
    void registerStaysInFlrBank() {
        for (int seed = 0; seed < 50; seed++) {
            setUp();
            placeRegister(5, 5, RegisterBank.FLR.base + 1); // FLR1
            GeneSubstitutionPlugin plugin = registerOnlyPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);

            int newValue = environment.getMolecule(5, 5).value();
            assertThat(newValue).as("seed=%d", seed)
                    .isBetween(RegisterBank.FLR.base, RegisterBank.FLR.base + Config.NUM_FLR_REGISTERS - 1);
        }
    }

    @Test
    void registerStaysInSdrBank() {
        for (int seed = 0; seed < 50; seed++) {
            setUp();
            placeRegister(5, 5, RegisterBank.SDR.base + 3); // SDR3
            GeneSubstitutionPlugin plugin = registerOnlyPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);

            int newValue = environment.getMolecule(5, 5).value();
            assertThat(newValue).as("seed=%d", seed)
                    .isBetween(RegisterBank.SDR.base, RegisterBank.SDR.base + Config.NUM_SDR_REGISTERS - 1);
        }
    }

    @Test
    void registerStaysInSlrBank() {
        for (int seed = 0; seed < 50; seed++) {
            setUp();
            placeRegister(5, 5, RegisterBank.SLR.base + 1); // SLR1
            GeneSubstitutionPlugin plugin = registerOnlyPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);

            int newValue = environment.getMolecule(5, 5).value();
            assertThat(newValue).as("seed=%d", seed)
                    .isBetween(RegisterBank.SLR.base, RegisterBank.SLR.base + Config.NUM_SLR_REGISTERS - 1);
        }
    }

    @Test
    void registerClampsAtBankBoundaries() {
        // Test boundary clamping for each new bank (PLR, FLR, SDR, SLR)
        for (RegisterBank bank : new RegisterBank[]{RegisterBank.PLR, RegisterBank.FLR, RegisterBank.SDR, RegisterBank.SLR}) {
            if (bank.count == 0) continue;
            for (int seed = 0; seed < 50; seed++) {
                setUp();
                // Place at lower boundary
                placeRegister(5, 5, bank.base);
                GeneSubstitutionPlugin plugin = registerOnlyPlugin(new SeededRandomProvider(seed));
                plugin.substitute(child, environment);
                int newLow = environment.getMolecule(5, 5).value();
                assertThat(newLow).as("%s lower boundary, seed=%d", bank.name(), seed)
                        .isBetween(bank.base, bank.base + 1);

                setUp();
                // Place at upper boundary
                placeRegister(5, 5, bank.base + bank.count - 1);
                plugin = registerOnlyPlugin(new SeededRandomProvider(seed + 1000));
                plugin.substitute(child, environment);
                int newHigh = environment.getMolecule(5, 5).value();
                assertThat(newHigh).as("%s upper boundary, seed=%d", bank.name(), seed)
                        .isBetween(bank.base + bank.count - 2, bank.base + bank.count - 1);
            }
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
    void negativeDataMutatesToANeighbouringValue() {
        // |-1| = 1, exponent 0.5: delta = max(1, round(1)) = 1, so the result must stay within [-2, 0].
        int changed = 0;
        for (int seed = 0; seed < 50; seed++) {
            setUp();
            placeData(5, 5, -1);
            GeneSubstitutionPlugin plugin = dataOnlyPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);

            int newValue = environment.getMolecule(5, 5).value();
            assertThat(newValue).as("seed=%d: -1 must mutate to a neighbouring value", seed)
                    .isBetween(-2, 0);
            if (newValue != -1) {
                changed++;
            }
        }
        // Without this, the range assertion would also hold for a mutator that does nothing at all.
        assertThat(changed).as("at least one seed must change the value").isPositive();
    }

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
    void dataNearZeroCrossesTheSignBoundary() {
        // value=1, delta=max(1,round(1^0.5))=1, range=[0,2]
        for (int seed = 0; seed < 50; seed++) {
            setUp();
            placeData(5, 5, 1);
            GeneSubstitutionPlugin plugin = dataOnlyPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);

            int newValue = environment.getMolecule(5, 5).value();
            assertThat(newValue).as("seed=%d: value=1 should stay in [0,2]", seed)
                    .isBetween(0, 2);
        }

        // value=0, delta=max(1,round(0^0.5))=1, range=[-1,1]. The perturbation works on the signed
        // value, so the sign boundary is an ordinary step and not a barrier.
        int sawNegative = 0;
        for (int seed = 0; seed < 50; seed++) {
            setUp();
            placeData(5, 5, 0);
            GeneSubstitutionPlugin plugin = dataOnlyPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);

            int newValue = environment.getMolecule(5, 5).value();
            assertThat(newValue).as("seed=%d: value=0 should stay in [-1,1]", seed)
                    .isBetween(-1, 1);
            if (newValue == -1) {
                sawNegative++;
            }
        }
        assertThat(sawNegative).as("at least one seed must reach -1 from 0").isPositive();
    }

    /**
     * Magnitude and the delta it produces at the exponent 0.5 the test plugins use. The deltas are
     * written out rather than recomputed with the production formula, so a wrong formula cannot
     * confirm itself.
     */
    private static Stream<Arguments> dataMagnitudes() {
        return Stream.of(
                Arguments.of(0, 1),
                Arguments.of(1, 1),
                Arguments.of(100, 10),
                Arguments.of(10000, 100),
                Arguments.of(524287, 724));
    }

    /** Distance on the ring of 20-bit values, where 524287 and -524288 are neighbours. */
    private static int ringDistance(int a, int b) {
        int distance = Math.abs(a - b);
        return Math.min(distance, (1 << Config.VALUE_BITS) - distance);
    }

    @ParameterizedTest(name = "|value|={0}, delta={1}")
    @MethodSource("dataMagnitudes")
    void dataPerturbationDependsOnMagnitudeOnly(int magnitude, int delta) {
        int[] signs = magnitude == 0 ? new int[]{1} : new int[]{1, -1};
        for (int sign : signs) {
            int original = sign * magnitude;
            int largestStep = 0;
            for (int seed = 0; seed < 50; seed++) {
                setUp();
                placeData(5, 5, original);
                GeneSubstitutionPlugin plugin = dataOnlyPlugin(new SeededRandomProvider(seed));
                plugin.substitute(child, environment);

                int step = ringDistance(environment.getMolecule(5, 5).value(), original);
                assertThat(step).as("original=%d, seed=%d: step must not exceed delta", original, seed)
                        .isLessThanOrEqualTo(delta);
                largestStep = Math.max(largestStep, step);
            }
            // The upper bound alone would also hold for a mutator that only ever moves by one, so
            // the delta has to be shown to be in use. Requiring the maximum to reach delta exactly
            // would be flaky: at delta 724 that happens within 50 seeds in about 7% of cases.
            assertThat(largestStep).as("original=%d: largest observed step", original)
                    .isGreaterThanOrEqualTo((delta + 1) / 2);
        }
    }

    @Test
    void dataWrapsAtTheRangeBoundary() {
        // At 524287 the delta is 724, so about half the seeds leave the range. Wrapping continues at
        // the opposite end. This needs its own assertion: the bound above cannot detect a clamp,
        // because a clamp moves the value less, never more.
        int sawWrap = 0;
        for (int seed = 0; seed < 50; seed++) {
            setUp();
            placeData(5, 5, 524287);
            GeneSubstitutionPlugin plugin = dataOnlyPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);

            if (environment.getMolecule(5, 5).value() < 0) {
                sawWrap++;
            }
        }
        assertThat(sawWrap).as("at least one seed must wrap past the upper bound").isPositive();
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

    // ---- Mutation record tests ----

    @Test
    void aWriteIsRecordedWithItsCellAndTheMoleculesAroundIt() {
        // The one changed cell, at a flat index the environment persists by
        int flatIndex = environment.getProperties().toFlatIndex(new int[]{5, 5});
        int mutatedSeed = -1;
        int expectedOld = new Molecule(Config.TYPE_CODE, ADDR_OPCODE, 0).toInt();

        for (int seed = 0; seed < 100 && mutatedSeed < 0; seed++) {
            setUp();
            placeCode(5, 5, ADDR_OPCODE);
            GeneSubstitutionPlugin plugin = codeOnlyPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);
            if (environment.getMolecule(5, 5).value() != ADDR_OPCODE) {
                mutatedSeed = seed;
            }
        }
        assertThat(mutatedSeed).as("at least one seed of 100 mutates the opcode").isNotNegative();

        List<MutationRecord> records = child.getBirthMutations();
        assertThat(records).hasSize(1);
        MutationRecord record = records.get(0);
        assertThat(record.pluginClass()).isEqualTo(GeneSubstitutionPlugin.class.getName());
        assertThat(record.kind()).isEqualTo("substitution");
        assertThat(record.cells()).containsExactly(flatIndex);
        assertThat(record.oldValues()).containsExactly(expectedOld);
        assertThat(record.newValues()).containsExactly(environment.getMolecule(5, 5).toInt());
        assertThat(record.params()).isEmpty();
        assertThat(record.dv()).isEqualTo(child.getDv());
    }

    @Test
    void theRecordKeepsTheMarkerOfTheCellInBothValues() {
        boolean recorded = false;
        for (int seed = 0; seed < 50 && !recorded; seed++) {
            setUp();
            environment.setMolecule(new Molecule(Config.TYPE_DATA, 100, 5), child.getId(), new int[]{5, 5});
            GeneSubstitutionPlugin plugin = dataOnlyPlugin(new SeededRandomProvider(seed));
            plugin.substitute(child, environment);

            List<MutationRecord> records = child.getBirthMutations();
            if (records != null) {
                MutationRecord record = records.get(0);
                assertThat(Molecule.fromInt(record.oldValues()[0]).marker()).isEqualTo(5);
                assertThat(Molecule.fromInt(record.newValues()[0]).marker()).isEqualTo(5);
                assertThat(record.oldValues()[0]).isNotEqualTo(record.newValues()[0]);
                recorded = true;
            }
        }
        assertThat(recorded).as("at least one seed of 50 mutates the value").isTrue();
    }

    @Test
    void aRunThatChangesNothingRecordsNothing() {
        // A register outside every bank has no neighbour to move to, so the mutation returns the
        // value it was given and the plugin writes nothing
        placeRegister(5, 5, 200);
        GeneSubstitutionPlugin plugin = registerOnlyPlugin(new SeededRandomProvider(42));

        plugin.substitute(child, environment);

        assertThat(environment.getMolecule(5, 5).value()).isEqualTo(200);
        assertThat(child.getBirthMutations()).isNull();
    }

    @Test
    void aRunThatFindsNothingToMutateRecordsNothing() {
        GeneSubstitutionPlugin plugin = allTypesPlugin(new SeededRandomProvider(42));

        plugin.substitute(child, environment);

        assertThat(child.getBirthMutations()).isNull();
    }

    // ---- Configuration validation tests ----

    /** Creates a plugin with the given rate and DATA exponent, all other settings irrelevant. */
    private GeneSubstitutionPlugin pluginWith(double substitutionRate, double dataExponent) {
        return new GeneSubstitutionPlugin(new SeededRandomProvider(0), substitutionRate,
                1.0, 1.0, 1.0, 1.0, 1.0,
                0.7, 0.2, 0.1,
                dataExponent, 1, 1);
    }

    @Test
    void acceptsTheBoundsOfBothRanges() {
        assertThat(pluginWith(0.0, 0.0)).isNotNull();
        assertThat(pluginWith(1.0, 1.0)).isNotNull();
    }

    @Test
    void rejectsSubstitutionRateOutsideItsRange() {
        assertThatThrownBy(() -> pluginWith(-0.1, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("substitutionRate");
        assertThatThrownBy(() -> pluginWith(1.1, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("substitutionRate");
    }

    @Test
    void rejectsDataExponentOutsideItsRange() {
        // Above 1.0 the delta exceeds the value; from about 1.58 the int cast of the rounded
        // power truncates instead of saturating, which silently corrupts the delta.
        assertThatThrownBy(() -> pluginWith(1.0, -0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exponent");
        assertThatThrownBy(() -> pluginWith(1.0, 1.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exponent");
    }

    @Test
    void rejectsNaNForBothValues() {
        // NaN compares false to every bound, so a negated range check would let it through.
        assertThatThrownBy(() -> pluginWith(Double.NaN, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("substitutionRate");
        assertThatThrownBy(() -> pluginWith(1.0, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exponent");
    }

    // ---- Registry-driven configuration tests ----

    /**
     * Builds a plugin configuration with one block per registered molecule type, in which only the
     * named type carries a non-zero weight. Types that need more than a weight get their own
     * settings, so any type can be enabled without a second place to edit.
     */
    private static String configWithOnly(String enabledType) {
        StringBuilder text = new StringBuilder("substitutionRate = 1.0\n");
        for (int type : MoleculeTypeRegistry.orderedTypes()) {
            String name = MoleculeTypeRegistry.typeToName(type);
            text.append(name).append(" { weight = ").append(name.equals(enabledType) ? "1.0" : "0.0");
            if ("CODE".equals(name)) {
                text.append(", operationFlipWeight = 0.7, familyFlipWeight = 0.2, variantFlipWeight = 0.1");
            }
            if ("LABEL".equals(name) || "LABELREF".equals(name)) {
                text.append(", bitflips = 1");
            }
            text.append(" }\n");
        }
        return text.toString();
    }

    /** Places a molecule of the given type whose value the type's own strategy can change. */
    private void placeMutableMoleculeOf(int type) {
        int value;
        if (type == Config.TYPE_CODE) {
            value = ADDR_OPCODE;
        } else if (type == Config.TYPE_REGISTER) {
            value = 3;
        } else if (type == Config.TYPE_LABEL || type == Config.TYPE_LABELREF) {
            value = 12345;
        } else {
            value = 100;
        }
        environment.setMolecule(new Molecule(type, value), child.getId(), new int[]{5, 5});
    }

    /**
     * Every type the registry knows has a weight and a strategy, so registering a type is enough
     * to make it mutable. A type the plugin could not act on would stay untouched here.
     */
    @Test
    void everyRegisteredTypeIsMutableWhenItsWeightIsSet() {
        for (int type : MoleculeTypeRegistry.orderedTypes()) {
            String name = MoleculeTypeRegistry.typeToName(type);
            com.typesafe.config.Config config = ConfigFactory.parseString(configWithOnly(name));

            boolean mutated = false;
            for (int seed = 0; seed < 100 && !mutated; seed++) {
                setUp();
                placeMutableMoleculeOf(type);
                new GeneSubstitutionPlugin(new SeededRandomProvider(seed), config)
                        .substitute(child, environment);

                Molecule molecule = environment.getMolecule(5, 5);
                assertThat(molecule.type()).as("%s keeps its type", name).isEqualTo(type);
                mutated = molecule.value() != (type == Config.TYPE_CODE ? ADDR_OPCODE
                        : type == Config.TYPE_REGISTER ? 3
                        : type == Config.TYPE_LABEL || type == Config.TYPE_LABELREF ? 12345
                        : 100);
            }
            assertThat(mutated).as("%s is mutated at weight 1", name).isTrue();
        }
    }

    @Test
    void stateIsNeverSelectedAtWeightZero() {
        com.typesafe.config.Config config = ConfigFactory.parseString(configWithOnly("CODE"));

        for (int seed = 0; seed < 50; seed++) {
            setUp();
            placeCode(5, 5, ADDR_OPCODE);
            environment.setMolecule(new Molecule(Config.TYPE_STATE, 89), child.getId(), new int[]{10, 5});

            new GeneSubstitutionPlugin(new SeededRandomProvider(seed), config)
                    .substitute(child, environment);

            assertThat(environment.getMolecule(10, 5).value())
                    .as("seed=%d: STATE with weight 0 is never selected", seed)
                    .isEqualTo(89);
        }
    }

    @Test
    void aMissingTypeBlockMeansWeightZero() {
        // No STATE block at all, so nothing may happen to a STATE cell
        com.typesafe.config.Config config = ConfigFactory.parseString("""
                substitutionRate = 1.0
                CODE { weight = 1.0, operationFlipWeight = 0.7, familyFlipWeight = 0.2, variantFlipWeight = 0.1 }
                """);

        for (int seed = 0; seed < 50; seed++) {
            setUp();
            placeCode(5, 5, ADDR_OPCODE);
            environment.setMolecule(new Molecule(Config.TYPE_STATE, 89), child.getId(), new int[]{10, 5});

            new GeneSubstitutionPlugin(new SeededRandomProvider(seed), config)
                    .substitute(child, environment);

            assertThat(environment.getMolecule(10, 5).value())
                    .as("seed=%d: a type without a block is never selected", seed)
                    .isEqualTo(89);
        }
    }

    /**
     * A block whose name is neither the substitution rate nor a molecule type would be read by
     * nothing, so it is rejected instead of being silently ignored.
     */
    @Test
    void anUnknownBlockNameIsRejected() {
        com.typesafe.config.Config config = ConfigFactory.parseString("""
                substitutionRate = 1.0
                DATTA { weight = 1.0 }
                """);

        assertThatThrownBy(() -> new GeneSubstitutionPlugin(new SeededRandomProvider(1), config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DATTA")
                .hasMessageContaining("substitutionRate")
                .hasMessageContaining("DATA");
    }

    @Test
    void aConfiguredStateWeightPerturbsTheValue() {
        com.typesafe.config.Config config = ConfigFactory.parseString(configWithOnly("STATE"));

        boolean steppedBeyondOne = false;
        for (int seed = 0; seed < 50; seed++) {
            setUp();
            environment.setMolecule(new Molecule(Config.TYPE_STATE, 100), child.getId(), new int[]{5, 5});

            new GeneSubstitutionPlugin(new SeededRandomProvider(seed), config)
                    .substitute(child, environment);

            Molecule molecule = environment.getMolecule(5, 5);
            assertThat(molecule.type()).isEqualTo(Config.TYPE_STATE);
            // delta = round(100^0.7) = 25, so the value stays in the neighbourhood of 100
            assertThat(molecule.value()).as("seed=%d: scale-proportional step", seed).isBetween(75, 125);
            if (Math.abs(molecule.value() - 100) > 1) {
                steppedBeyondOne = true;
            }
        }
        // A step of more than 1 can only come from the configured exponent: an exponent of 0.0
        // would make every delta max(1, round(1)) = 1 and leave the value at 99, 100 or 101.
        assertThat(steppedBeyondOne)
                .as("a STATE value is perturbed scale-proportionally at weight 1")
                .isTrue();
    }

    /**
     * The exponent of a value-carrying type defaults where its block names none, so a block that
     * only sets a weight still produces the same perturbation as the documented default.
     */
    @Test
    void aValueBlockWithoutAnExponentUsesTheDefault() {
        com.typesafe.config.Config config = ConfigFactory.parseString("""
                substitutionRate = 1.0
                STATE { weight = 1.0 }
                """);

        boolean steppedBeyondOne = false;
        for (int seed = 0; seed < 50; seed++) {
            setUp();
            environment.setMolecule(new Molecule(Config.TYPE_STATE, 100), child.getId(), new int[]{5, 5});

            new GeneSubstitutionPlugin(new SeededRandomProvider(seed), config)
                    .substitute(child, environment);

            int value = environment.getMolecule(5, 5).value();
            assertThat(value)
                    .as("seed=%d: delta = round(100^0.7) = 25", seed)
                    .isBetween(75, 125);
            if (Math.abs(value - 100) > 1) {
                steppedBeyondOne = true;
            }
        }
        // The default exponent is what produces a step of more than 1: an exponent of 0.0 would
        // make every delta max(1, round(1)) = 1 and leave the value at 99, 100 or 101.
        assertThat(steppedBeyondOne)
                .as("the default exponent produces a scale-proportional step")
                .isTrue();
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
