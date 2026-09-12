package org.evochora.runtime.model;

import org.evochora.runtime.Config;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link GenomeRule}.
 * <p>
 * Cover the two forms of an entry - a bare type name and a {@code TYPE:VALUE} specification -
 * the independence from marker bits, and the rejection of entries that cannot be read.
 */
@Tag("unit")
class GenomeRuleTest {

    @Test
    void typeNameExcludesEveryMoleculeOfThatType() {
        GenomeRule rule = new GenomeRule(List.of("STATE"));

        assertThat(rule.excludes(new Molecule(Config.TYPE_STATE, 0).toInt())).isTrue();
        assertThat(rule.excludes(new Molecule(Config.TYPE_STATE, 42).toInt())).isTrue();
        assertThat(rule.excludes(new Molecule(Config.TYPE_STATE, -7).toInt())).isTrue();
        assertThat(rule.excludes(new Molecule(Config.TYPE_DATA, 42).toInt())).isFalse();
    }

    @Test
    void typeNameIsCaseInsensitiveAndTrimmed() {
        GenomeRule rule = new GenomeRule(List.of("  state  "));

        assertThat(rule.excludes(new Molecule(Config.TYPE_STATE, 1).toInt())).isTrue();
    }

    @Test
    void typedValueExcludesOnlyThatValue() {
        GenomeRule rule = new GenomeRule(List.of("STRUCTURE:100"));

        assertThat(rule.excludes(new Molecule(Config.TYPE_STRUCTURE, 100).toInt())).isTrue();
        assertThat(rule.excludes(new Molecule(Config.TYPE_STRUCTURE, 99).toInt())).isFalse();
        assertThat(rule.excludes(new Molecule(Config.TYPE_DATA, 100).toInt())).isFalse();
    }

    @Test
    void negativeValueIsRead() {
        GenomeRule rule = new GenomeRule(List.of("DATA:-1"));

        assertThat(rule.excludes(new Molecule(Config.TYPE_DATA, -1).toInt())).isTrue();
        assertThat(rule.excludes(new Molecule(Config.TYPE_DATA, 1).toInt())).isFalse();
    }

    @Test
    void markerBitsAreIgnored() {
        GenomeRule rule = new GenomeRule(List.of("STATE", "STRUCTURE:100"));

        for (int marker = 0; marker < 16; marker++) {
            assertThat(rule.excludes(new Molecule(Config.TYPE_STATE, 3, marker).toInt()))
                    .as("STATE with marker " + marker)
                    .isTrue();
            assertThat(rule.excludes(new Molecule(Config.TYPE_STRUCTURE, 100, marker).toInt()))
                    .as("STRUCTURE:100 with marker " + marker)
                    .isTrue();
            assertThat(rule.excludes(new Molecule(Config.TYPE_STRUCTURE, 101, marker).toInt()))
                    .as("STRUCTURE:101 with marker " + marker)
                    .isFalse();
        }
    }

    @Test
    void bothEntryFormsApplyTogether() {
        GenomeRule rule = new GenomeRule(GenomeRule.DEFAULT_ENTRIES);

        assertThat(rule.excludes(new Molecule(Config.TYPE_STATE, 5).toInt())).isTrue();
        assertThat(rule.excludes(new Molecule(Config.TYPE_STRUCTURE, 100).toInt())).isTrue();
        assertThat(rule.excludes(new Molecule(Config.TYPE_STRUCTURE, 5).toInt())).isFalse();
        assertThat(rule.excludes(new Molecule(Config.TYPE_CODE, 0).toInt())).isFalse();
    }

    @Test
    void emptyListExcludesNothing() {
        GenomeRule rule = new GenomeRule(List.of());

        for (int type : MoleculeTypeRegistry.orderedTypes()) {
            assertThat(rule.excludes(new Molecule(type, 7).toInt()))
                    .as(MoleculeTypeRegistry.typeToName(type))
                    .isFalse();
        }
        assertThat(rule.excludes(new Molecule(Config.TYPE_CODE, 0).toInt())).isFalse();
    }

    @Test
    void unknownTypeNameIsRejected() {
        assertThatThrownBy(() -> new GenomeRule(List.of("FOOD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(GenomeRule.CONFIG_KEY)
                .hasMessageContaining("FOOD");
    }

    @Test
    void unknownTypeNameInTypedValueIsRejected() {
        assertThatThrownBy(() -> new GenomeRule(List.of("FOOD:100")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(GenomeRule.CONFIG_KEY)
                .hasMessageContaining("FOOD:100");
    }

    @Test
    void unreadableValueIsRejected() {
        assertThatThrownBy(() -> new GenomeRule(List.of("STRUCTURE:abc")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(GenomeRule.CONFIG_KEY)
                .hasMessageContaining("STRUCTURE:abc");
    }

    @Test
    void entryWithTooManyPartsIsRejected() {
        assertThatThrownBy(() -> new GenomeRule(List.of("STRUCTURE:100:2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(GenomeRule.CONFIG_KEY)
                .hasMessageContaining("STRUCTURE:100:2");
    }
}
