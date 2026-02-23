package org.evochora.runtime.worldgen;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LabelRewritePlugin}.
 */
@Tag("unit")
class LabelRewritePluginTest {

    private Environment environment;

    /** The child organism that was just born. */
    private Organism child;

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

    @Test
    void rewritesLabelAndLabelRefValues() {
        int labelValue = 12345;
        int labelRefValue = 12340; // slightly different

        environment.setMolecule(new Molecule(Config.TYPE_LABEL, labelValue), child.getId(), new int[]{0, 0});
        environment.setMolecule(new Molecule(Config.TYPE_LABELREF, labelRefValue), child.getId(), new int[]{1, 0});

        LabelRewritePlugin plugin = new LabelRewritePlugin(new SeededRandomProvider(42L));
        plugin.onBirth(child, environment);

        assertThat(environment.getMolecule(0, 0).value() & Config.VALUE_MASK)
                .as("LABEL value should be rewritten")
                .isNotEqualTo(labelValue);
        assertThat(environment.getMolecule(1, 0).value() & Config.VALUE_MASK)
                .as("LABELREF value should be rewritten")
                .isNotEqualTo(labelRefValue);
    }

    @Test
    void preservesHammingDistance() {
        int labelValue = 0b1010_1010_1010_1010_101;  // 19 bits
        int labelRefValue = 0b1010_1010_1010_1010_100;  // 1 bit different (bit 0)
        int expectedHamming = Integer.bitCount(labelValue ^ labelRefValue);
        assertThat(expectedHamming).isEqualTo(1);

        environment.setMolecule(new Molecule(Config.TYPE_LABEL, labelValue), child.getId(), new int[]{0, 0});
        environment.setMolecule(new Molecule(Config.TYPE_LABELREF, labelRefValue), child.getId(), new int[]{1, 0});

        LabelRewritePlugin plugin = new LabelRewritePlugin(new SeededRandomProvider(42L));
        plugin.onBirth(child, environment);

        int newLabel = environment.getMolecule(0, 0).value() & Config.VALUE_MASK;
        int newLabelRef = environment.getMolecule(1, 0).value() & Config.VALUE_MASK;
        int actualHamming = Integer.bitCount(newLabel ^ newLabelRef);

        assertThat(actualHamming)
                .as("Hamming distance between LABEL and LABELREF must be preserved")
                .isEqualTo(expectedHamming);
    }

    @Test
    void preservesHammingDistanceWithTwoBitDifference() {
        int labelValue = 0b0000_0000_0000_0000_111;
        int labelRefValue = 0b0000_0000_0000_0000_100;  // 2 bits different
        int expectedHamming = Integer.bitCount(labelValue ^ labelRefValue);
        assertThat(expectedHamming).isEqualTo(2);

        environment.setMolecule(new Molecule(Config.TYPE_LABEL, labelValue), child.getId(), new int[]{0, 0});
        environment.setMolecule(new Molecule(Config.TYPE_LABELREF, labelRefValue), child.getId(), new int[]{1, 0});

        LabelRewritePlugin plugin = new LabelRewritePlugin(new SeededRandomProvider(99L));
        plugin.onBirth(child, environment);

        int newLabel = environment.getMolecule(0, 0).value() & Config.VALUE_MASK;
        int newLabelRef = environment.getMolecule(1, 0).value() & Config.VALUE_MASK;

        assertThat(Integer.bitCount(newLabel ^ newLabelRef))
                .as("Hamming distance of 2 must be preserved")
                .isEqualTo(expectedHamming);
    }

    @Test
    void doesNotModifyNonLabelMolecules() {
        int codeValue = 42;
        int dataValue = 1000;
        int energyValue = 500;
        int structureValue = 100;
        int registerValue = 3;

        environment.setMolecule(new Molecule(Config.TYPE_CODE, codeValue), child.getId(), new int[]{0, 0});
        environment.setMolecule(new Molecule(Config.TYPE_DATA, dataValue), child.getId(), new int[]{1, 0});
        environment.setMolecule(new Molecule(Config.TYPE_ENERGY, energyValue), child.getId(), new int[]{2, 0});
        environment.setMolecule(new Molecule(Config.TYPE_STRUCTURE, structureValue), child.getId(), new int[]{3, 0});
        environment.setMolecule(new Molecule(Config.TYPE_REGISTER, registerValue), child.getId(), new int[]{4, 0});

        LabelRewritePlugin plugin = new LabelRewritePlugin(new SeededRandomProvider(42L));
        plugin.onBirth(child, environment);

        assertThat(environment.getMolecule(0, 0).value()).isEqualTo(codeValue);
        assertThat(environment.getMolecule(1, 0).value()).isEqualTo(dataValue);
        assertThat(environment.getMolecule(2, 0).value()).isEqualTo(energyValue);
        assertThat(environment.getMolecule(3, 0).value()).isEqualTo(structureValue);
        assertThat(environment.getMolecule(4, 0).value()).isEqualTo(registerValue);
    }

    @Test
    void preservesOwnership() {
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, 12345), child.getId(), new int[]{0, 0});
        environment.setMolecule(new Molecule(Config.TYPE_LABELREF, 12340), child.getId(), new int[]{1, 0});
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 42), child.getId(), new int[]{2, 0});

        LabelRewritePlugin plugin = new LabelRewritePlugin(new SeededRandomProvider(42L));
        plugin.onBirth(child, environment);

        assertThat(environment.getOwnerId(0, 0)).isEqualTo(child.getId());
        assertThat(environment.getOwnerId(1, 0)).isEqualTo(child.getId());
        assertThat(environment.getOwnerId(2, 0)).isEqualTo(child.getId());
    }

    @Test
    void labelIndexUpdatedAfterRewrite() {
        int originalHash = 12345;
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, originalHash), child.getId(), new int[]{5, 5});

        // Before rewrite: label index should find the original hash
        int beforeResult = environment.getLabelIndex().findTarget(
                originalHash, child.getId(), new int[]{5, 5}, environment);
        assertThat(beforeResult).as("Label should be found before rewrite").isGreaterThanOrEqualTo(0);

        LabelRewritePlugin plugin = new LabelRewritePlugin(new SeededRandomProvider(42L));
        plugin.onBirth(child, environment);

        // After rewrite: original hash should no longer be found
        int afterOriginal = environment.getLabelIndex().findTarget(
                originalHash, child.getId(), new int[]{5, 5}, environment);
        assertThat(afterOriginal).as("Original label hash should not be found after rewrite").isEqualTo(-1);

        // After rewrite: new hash should be found
        int newHash = environment.getMolecule(5, 5).value() & Config.VALUE_MASK;
        int afterNew = environment.getLabelIndex().findTarget(
                newHash, child.getId(), new int[]{5, 5}, environment);
        assertThat(afterNew).as("Rewritten label hash should be found").isGreaterThanOrEqualTo(0);
    }

    @Test
    void appliesSameMaskToAllMolecules() {
        int label1 = 100;
        int label2 = 200;
        int labelRef1 = 300;

        environment.setMolecule(new Molecule(Config.TYPE_LABEL, label1), child.getId(), new int[]{0, 0});
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, label2), child.getId(), new int[]{1, 0});
        environment.setMolecule(new Molecule(Config.TYPE_LABELREF, labelRef1), child.getId(), new int[]{2, 0});

        LabelRewritePlugin plugin = new LabelRewritePlugin(new SeededRandomProvider(42L));
        plugin.onBirth(child, environment);

        int newLabel1 = environment.getMolecule(0, 0).value() & Config.VALUE_MASK;
        int newLabel2 = environment.getMolecule(1, 0).value() & Config.VALUE_MASK;
        int newLabelRef1 = environment.getMolecule(2, 0).value() & Config.VALUE_MASK;

        // All three should have been XOR'd with the same mask.
        // Verify: mask = oldValue ^ newValue should be identical for all.
        int mask1 = label1 ^ newLabel1;
        int mask2 = label2 ^ newLabel2;
        int mask3 = labelRef1 ^ newLabelRef1;

        assertThat(mask1).as("Same mask applied to all molecules").isEqualTo(mask2);
        assertThat(mask1).isEqualTo(mask3);
        assertThat(mask1).as("Mask should be non-zero").isNotEqualTo(0);
    }

    @Test
    void deterministicWithSeed() {
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, 12345), child.getId(), new int[]{0, 0});
        environment.setMolecule(new Molecule(Config.TYPE_LABELREF, 54321), child.getId(), new int[]{1, 0});

        // First run
        LabelRewritePlugin plugin1 = new LabelRewritePlugin(new SeededRandomProvider(42L));
        plugin1.onBirth(child, environment);
        int firstLabel = environment.getMolecule(0, 0).value();
        int firstLabelRef = environment.getMolecule(1, 0).value();

        // Reset molecules to original values
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, 12345), child.getId(), new int[]{0, 0});
        environment.setMolecule(new Molecule(Config.TYPE_LABELREF, 54321), child.getId(), new int[]{1, 0});

        // Second run with same seed
        LabelRewritePlugin plugin2 = new LabelRewritePlugin(new SeededRandomProvider(42L));
        plugin2.onBirth(child, environment);
        int secondLabel = environment.getMolecule(0, 0).value();
        int secondLabelRef = environment.getMolecule(1, 0).value();

        assertThat(firstLabel).isEqualTo(secondLabel);
        assertThat(firstLabelRef).isEqualTo(secondLabelRef);
    }

    @Test
    void statelessSerialization() {
        LabelRewritePlugin plugin = new LabelRewritePlugin(new SeededRandomProvider(42L));

        byte[] state = plugin.saveState();
        assertThat(state).isEmpty();

        // loadState should not throw
        plugin.loadState(new byte[0]);
    }

    @Test
    void handlesEmptyOrganism() {
        // Child owns no cells — onBirth should return without error
        LabelRewritePlugin plugin = new LabelRewritePlugin(new SeededRandomProvider(42L));
        plugin.onBirth(child, environment);
        // No assertion needed — just verifying no exception is thrown
    }
}
