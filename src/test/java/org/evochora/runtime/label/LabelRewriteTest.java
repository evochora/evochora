package org.evochora.runtime.label;

import com.typesafe.config.ConfigFactory;
import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.isa.RegisterBank;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.MutationRecord;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.ILabelMatchingStrategy;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The core step that moves a newborn into the label namespace its matching strategy chooses, and
 * that a strategy is a class anybody can plug in through the configuration.
 */
@Tag("unit")
class LabelRewriteTest {

    private static final int MASK = 0b1000_0000_0000_0101_0000;

    private Simulation simulation;
    private Environment environment;
    private Organism child;

    /** Builds a world whose label matching strategy is the one the configuration block names. */
    private void worldWith(String labelMatchingBlock) {
        ILabelMatchingStrategy strategy = Environment.createLabelMatchingStrategy(
                labelMatchingBlock == null ? null : ConfigFactory.parseString(labelMatchingBlock));
        environment = new Environment(new EnvironmentProperties(new int[]{32, 32}, true), strategy);
        ThermodynamicPolicyManager policyManager = new ThermodynamicPolicyManager(ConfigFactory.parseString("""
            default {
              className = "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy"
              options { base-energy = 1, base-entropy = 1 }
            }
            overrides { instructions {}, families {} }
            """));
        simulation = new Simulation(environment, policyManager, ConfigFactory.parseMap(Map.of(
                "max-energy", 32767, "max-entropy", 8191, "error-penalty-cost", 10)), 1);
        Organism parent = Organism.create(simulation, new int[]{0, 0}, 10000);
        simulation.addOrganism(parent);
        child = Organism.restore(2, 9)
                .parentId(parent.getId())
                .ip(new int[]{0, 0})
                .dv(new int[]{1, 0})
                .initialPosition(new int[]{0, 0})
                .energy(5000)
                .registers(defaultRegisters())
                .dataPointers(defaultDataPointers())
                .build(simulation);
        simulation.addOrganism(child);
    }

    private static String exactStrategyWithMask(int mask) {
        return "className = \"" + ExactLabelMatchingStrategy.class.getName() + "\", options { birthMask = " + mask + " }";
    }

    @AfterEach
    void shutdown() {
        if (simulation != null) {
            simulation.shutdown();
        }
    }

    private static Object[] defaultRegisters() {
        Object[] registers = new Object[RegisterBank.TOTAL_REGISTER_COUNT];
        for (int slot = 0; slot < registers.length; slot++) {
            RegisterBank bank = RegisterBank.SLOT_TO_BANK[slot];
            registers[slot] = bank != null && bank.isLocation ? new int[]{0, 0} : 0;
        }
        return registers;
    }

    private static List<int[]> defaultDataPointers() {
        List<int[]> pointers = new ArrayList<>(Config.NUM_DATA_POINTERS);
        for (int i = 0; i < Config.NUM_DATA_POINTERS; i++) {
            pointers.add(new int[]{0, 0});
        }
        return pointers;
    }

    private int valueAt(int x, int y) {
        return Molecule.extractTypedValue(environment.getMolecule(x, y).toInt());
    }

    @Test
    void aStrategyIsLoadedByItsClassNameAndReceivesItsOptions() {
        worldWith(exactStrategyWithMask(MASK));

        ILabelMatchingStrategy strategy = environment.getLabelIndex().getStrategy();

        assertThat(strategy).isInstanceOf(ExactLabelMatchingStrategy.class);
        assertThat(strategy.birthMask(new SeededRandomProvider(1L))).isEqualTo(MASK);
    }

    @Test
    void lookupsAreServedByThePluggedInStrategy() {
        worldWith(exactStrategyWithMask(0));
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, 12345), child.getId(), new int[]{3, 4});

        int exact = environment.getLabelIndex().findTarget(12345, child.getId(), new int[]{0, 0}, child.getRandom());
        int oneBitOff = environment.getLabelIndex().findTarget(12344, child.getId(), new int[]{0, 0}, child.getRandom());

        assertThat(exact).isEqualTo(environment.getProperties().toFlatIndex(new int[]{3, 4}));
        assertThat(oneBitOff).as("this strategy knows no tolerance").isEqualTo(-1);
    }

    @Test
    void theMaskMovesEveryLabelAndLabelReferenceAndNothingElse() {
        worldWith(exactStrategyWithMask(MASK));
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, 12345), child.getId(), new int[]{0, 0});
        environment.setMolecule(new Molecule(Config.TYPE_LABELREF, 12340), child.getId(), new int[]{1, 0});
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 12345), child.getId(), new int[]{2, 0});
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, 777), 1, new int[]{5, 5});

        new LabelRewrite().apply(child, environment, new SeededRandomProvider(42L));

        assertThat(valueAt(0, 0)).isEqualTo(12345 ^ MASK);
        assertThat(valueAt(1, 0)).isEqualTo(12340 ^ MASK);
        assertThat(valueAt(2, 0)).as("a number is no label address").isEqualTo(12345);
        assertThat(valueAt(5, 5)).as("the parent's label is not the newborn's").isEqualTo(777);
        assertThat(Integer.bitCount(valueAt(0, 0) ^ valueAt(1, 0)))
                .as("the Hamming distance inside the organism is what it was")
                .isEqualTo(Integer.bitCount(12345 ^ 12340));
    }

    @Test
    void theRewrittenLabelIsFoundUnderItsNewValue() {
        worldWith(exactStrategyWithMask(MASK));
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, 12345), child.getId(), new int[]{3, 4});

        new LabelRewrite().apply(child, environment, new SeededRandomProvider(42L));

        int flatIndex = environment.getProperties().toFlatIndex(new int[]{3, 4});
        assertThat(environment.getLabelIndex().findTarget(12345 ^ MASK, child.getId(), new int[]{0, 0}, child.getRandom()))
                .isEqualTo(flatIndex);
        assertThat(environment.getLabelIndex().findTarget(12345, child.getId(), new int[]{0, 0}, child.getRandom()))
                .isEqualTo(-1);
    }

    @Test
    void aRewriteIsRecordedWithItsMaskNoCellsAndTheStrategyAsItsSource() {
        worldWith(exactStrategyWithMask(MASK));
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, 12345), child.getId(), new int[]{0, 0});

        new LabelRewrite().apply(child, environment, new SeededRandomProvider(42L));

        List<MutationRecord> records = child.getBirthMutations();
        assertThat(records).hasSize(1);
        MutationRecord record = records.get(0);
        assertThat(record.pluginClass()).isEqualTo(ExactLabelMatchingStrategy.class.getName());
        assertThat(record.kind()).isEqualTo(LabelRewrite.MUTATION_KIND).isEqualTo("label-rewrite");
        assertThat(record.cells()).as("the mask changes no molecule of its own").isEmpty();
        assertThat(record.oldValues()).isEmpty();
        assertThat(record.newValues()).isEmpty();
        assertThat(record.params()).containsExactly(MASK);
        assertThat(record.dv()).isEqualTo(child.getDv());
    }

    @Test
    void aMaskOfZeroChangesAndRecordsNothing() {
        worldWith(exactStrategyWithMask(0));
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, 12345), child.getId(), new int[]{0, 0});

        new LabelRewrite().apply(child, environment, new SeededRandomProvider(42L));

        assertThat(valueAt(0, 0)).isEqualTo(12345);
        assertThat(child.getBirthMutations()).isNull();
    }

    @Test
    void aGenomeWithoutLabelAddressesRecordsNothing() {
        worldWith(exactStrategyWithMask(MASK));
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 42), child.getId(), new int[]{0, 0});

        new LabelRewrite().apply(child, environment, new SeededRandomProvider(42L));

        assertThat(child.getBirthMutations()).isNull();
    }

    @Test
    void aNewbornWithoutCellsDoesNotCostTheStrategyARandomNumber() {
        worldWith("className = \"" + HammingLabelMatchingStrategy.class.getName()
                + "\", options { namespaceFlipRate = 1.0 }");
        SeededRandomProvider provider = new SeededRandomProvider(42L);
        byte[] before = provider.saveState();

        new LabelRewrite().apply(child, environment, provider);

        assertThat(provider.saveState()).isEqualTo(before);
    }

    @Test
    void aMaskOutsideTheValueFieldIsRefused() {
        worldWith(exactStrategyWithMask(1 << Config.VALUE_BITS));
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, 12345), child.getId(), new int[]{0, 0});

        assertThatThrownBy(() -> new LabelRewrite().apply(child, environment, new SeededRandomProvider(42L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ExactLabelMatchingStrategy.class.getName());
    }

    @Test
    void withoutAConfigurationTheDefaultStrategyIsUsed() {
        worldWith(null);

        assertThat(environment.getLabelIndex().getStrategy()).isInstanceOf(HammingLabelMatchingStrategy.class);
    }
}
