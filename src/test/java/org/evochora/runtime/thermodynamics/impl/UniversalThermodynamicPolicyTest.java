package org.evochora.runtime.thermodynamics.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.typesafe.config.ConfigFactory;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.spi.thermodynamics.IThermodynamicPolicy.Thermodynamics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UniversalThermodynamicPolicy}: base values, the resolution of read and
 * write rules from a packed molecule, and the value-specific overrides.
 * <p>
 * The policy prices one effect at a time and knows nothing about instructions. Which effects an
 * instruction records - and that a failed one records none - is covered by
 * {@code org.evochora.runtime.EffectLogTest}.
 */
@Tag("unit")
class UniversalThermodynamicPolicyTest {

    private static final int ACTOR = 1;
    private static final boolean WRITE = true;
    private static final boolean READ = false;

    private static UniversalThermodynamicPolicy policy(String config) {
        var policy = new UniversalThermodynamicPolicy();
        policy.initialize(ConfigFactory.parseString(config));
        return policy;
    }

    private static int packed(int type, int value) {
        return new Molecule(type, value, 0).toInt();
    }

    @Test
    void baseValuesAreReportedAsConfigured() {
        var policy = policy("base-energy = 3\nbase-entropy = -4\n");
        assertThat(policy.baseEnergy()).isEqualTo(3);
        assertThat(policy.baseEntropy()).isEqualTo(-4);
    }

    @Test
    void anEffectWithoutAnyRuleIsFree() {
        var policy = policy("base-energy = 1\nbase-entropy = 1\n");
        assertThat(policy.priceEffect(WRITE, packed(Config.TYPE_CODE, 42), 0, ACTOR)).isEqualTo(new Thermodynamics(0, 0));
        assertThat(policy.priceEffect(READ, packed(Config.TYPE_CODE, 42), 0, ACTOR)).isEqualTo(new Thermodynamics(0, 0));
    }

    @Test
    void writeRuleValueOverrideAppliesToMatchingValue() {
        var policy = policy("""
            write-rules: {
              CODE: {
                energy = 5, entropy = -50
                values: { "0": { energy = 1, entropy = -10 } }
              }
            }
            """);

        assertThat(policy.priceEffect(WRITE, packed(Config.TYPE_CODE, 0), 0, ACTOR)).isEqualTo(new Thermodynamics(1, -10));
        assertThat(policy.priceEffect(WRITE, packed(Config.TYPE_CODE, 42), 0, ACTOR)).isEqualTo(new Thermodynamics(5, -50));
    }

    @Test
    void writeRuleWithoutValuesBlockPricesEveryValueTheSame() {
        var policy = policy("write-rules: { CODE: { energy = 5, entropy = -50 } }");

        assertThat(policy.priceEffect(WRITE, packed(Config.TYPE_CODE, 0), 0, ACTOR).energyCost()).isEqualTo(5);
        assertThat(policy.priceEffect(WRITE, packed(Config.TYPE_CODE, 42), 0, ACTOR).energyCost()).isEqualTo(5);
    }

    @Test
    void writeRuleFallsBackToTheDefaultTypeRule() {
        var policy = policy("write-rules: { _default: { energy = 7, entropy = -70 }, CODE: { energy = 5, entropy = -50 } }");

        assertThat(policy.priceEffect(WRITE, packed(Config.TYPE_CODE, 1), 0, ACTOR)).isEqualTo(new Thermodynamics(5, -50));
        assertThat(policy.priceEffect(WRITE, packed(Config.TYPE_DATA, 1), 0, ACTOR)).isEqualTo(new Thermodynamics(7, -70));
    }

    @Test
    void readRulesAreResolvedByTheOwnershipOfTheCell() {
        var policy = policy("""
            read-rules: {
              own:     { CODE: { energy = 1,   entropy = 500 } }
              foreign: { CODE: { energy = 500, entropy = 500 } }
              unowned: { CODE: { energy = 5,   entropy = 5 } }
            }
            """);
        int code = packed(Config.TYPE_CODE, 42);

        assertThat(policy.priceEffect(READ, code, ACTOR, ACTOR)).isEqualTo(new Thermodynamics(1, 500));
        assertThat(policy.priceEffect(READ, code, ACTOR + 1, ACTOR)).isEqualTo(new Thermodynamics(500, 500));
        assertThat(policy.priceEffect(READ, code, 0, ACTOR)).isEqualTo(new Thermodynamics(5, 5));
    }

    @Test
    void readRuleValueOverrideAppliesToMatchingValue() {
        var policy = policy("""
            read-rules: {
              own: {
                CODE: {
                  energy = 3, entropy = 1
                  values: { "0": { energy = 0, entropy = 0 } }
                }
              }
            }
            """);

        assertThat(policy.priceEffect(READ, packed(Config.TYPE_CODE, 0), ACTOR, ACTOR)).isEqualTo(new Thermodynamics(0, 0));
        assertThat(policy.priceEffect(READ, packed(Config.TYPE_CODE, 42), ACTOR, ACTOR)).isEqualTo(new Thermodynamics(3, 1));
    }

    @Test
    void writeRulesDoNotApplyToReadsAndReadRulesNotToWrites() {
        var policy = policy("""
            read-rules:  { unowned: { CODE: { energy = 5,  entropy = 5 } } }
            write-rules: { CODE:    { energy = 11, entropy = -11 } }
            """);
        int code = packed(Config.TYPE_CODE, 42);

        assertThat(policy.priceEffect(READ, code, 0, ACTOR)).isEqualTo(new Thermodynamics(5, 5));
        assertThat(policy.priceEffect(WRITE, code, 0, ACTOR)).isEqualTo(new Thermodynamics(11, -11));
    }

    /**
     * A molecule's value is stored in two's complement across the value bits, so a negative one
     * reads as a large positive number unless it is sign-extended. Permille rules multiply by that
     * value, which is where an unextended bit pattern would turn a small cost into an enormous one.
     */
    @Test
    void permilleRulesUseTheSignExtendedValue() {
        var policy = policy("""
            read-rules: { unowned: { DATA: { energy-permille = 1000, entropy-permille = 1000 } } }
            """);

        Thermodynamics positive = policy.priceEffect(READ, packed(Config.TYPE_DATA, 50), 0, ACTOR);
        Thermodynamics negative = policy.priceEffect(READ, packed(Config.TYPE_DATA, -50), 0, ACTOR);

        // The magnitude decides the price, and -50 has the magnitude of 50 - not of 2^20 - 50.
        assertThat(positive).isEqualTo(new Thermodynamics(50, 50));
        assertThat(negative).isEqualTo(new Thermodynamics(50, 50));
    }

    /**
     * A value override is keyed by the signed value, so the rule for -1 must be found for the
     * molecule that carries -1 and not for the one whose bit pattern happens to read as 2^20 - 1.
     */
    @Test
    void valueOverridesAreKeyedByTheSignedValue() {
        var policy = policy("""
            write-rules: {
              DATA: {
                energy = 5, entropy = -50
                values: { "-1": { energy = 9, entropy = -90 } }
              }
            }
            """);

        assertThat(policy.priceEffect(WRITE, packed(Config.TYPE_DATA, -1), 0, ACTOR)).isEqualTo(new Thermodynamics(9, -90));
        assertThat(policy.priceEffect(WRITE, packed(Config.TYPE_DATA, 1), 0, ACTOR)).isEqualTo(new Thermodynamics(5, -50));
    }

    @Test
    void fixedAndPermilleComponentsOfARuleAreAdded() {
        var policy = policy("""
            read-rules: { unowned: { ENERGY: { energy = 5, energy-permille = -1000, entropy = 0 } } }
            """);

        assertThat(policy.priceEffect(READ, packed(Config.TYPE_ENERGY, 1000), 0, ACTOR))
                .isEqualTo(new Thermodynamics(5 - 1000, 0));
    }

    /**
     * Production embeds the organism's marker register into every written molecule, so the packed
     * int a policy is handed carries marker bits. They belong to neither the type nor the value and
     * must not reach rule resolution.
     */
    @Test
    void theMarkerBitsOfAMoleculeDoNotAffectRuleResolution() {
        var policy = policy("""
            write-rules: {
              DATA: {
                energy = 5, entropy = -50
                values: { "50": { energy = 9, entropy = -90 } }
              }
            }
            """);

        int unmarked = new Molecule(Config.TYPE_DATA, 50, 0).toInt();
        int marked = new Molecule(Config.TYPE_DATA, 50, 7).toInt();

        assertThat(marked).isNotEqualTo(unmarked);
        assertThat(policy.priceEffect(WRITE, marked, 0, ACTOR))
                .isEqualTo(policy.priceEffect(WRITE, unmarked, 0, ACTOR))
                .isEqualTo(new Thermodynamics(9, -90));
    }
}
