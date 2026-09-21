package org.evochora.runtime.thermodynamics.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FixedCostPolicy}, the minimal example of the thermodynamic extension
 * point: it charges its configured base values and prices no effect.
 */
@Tag("unit")
class FixedCostPolicyTest {

    private static FixedCostPolicy policy(String config) {
        var policy = new FixedCostPolicy();
        policy.initialize(ConfigFactory.parseString(config));
        return policy;
    }

    @Test
    void chargesTheConfiguredBaseValuesAndPricesNoEffect() {
        var policy = policy("energy = 3\nentropy = -4\n");

        assertThat(policy.baseEnergy()).isEqualTo(3);
        assertThat(policy.baseEntropy()).isEqualTo(-4);
        assertThat(policy.priceEffect(true, 0, 0, 1)).isEqualTo(org.evochora.runtime.spi.thermodynamics.IThermodynamicPolicy.FREE);
    }

    @Test
    void fallsBackToOneWhereNothingIsConfigured() {
        var policy = policy("");

        assertThat(policy.baseEnergy()).isEqualTo(1);
        assertThat(policy.baseEntropy()).isEqualTo(1);
    }

    @Test
    void anUnknownOptionKeyIsRejected() {
        assertThatThrownBy(() -> policy("energy-cost = 2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("energy-cost")
                .hasMessageContaining("energy, entropy");
    }
}
