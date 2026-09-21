package org.evochora.cli.config;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.File;

import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Contract test for the configuration the project ships: a node started from it must be able to
 * build what the configuration describes.
 * <p>
 * The configuration is loaded the way a node loads it - the file merged over the classpath
 * defaults and resolved as a whole - because that, and not either file on its own, is what a run
 * is governed by. A key that only {@code reference.conf} carries reaches the simulation even when
 * {@code config/evochora.conf} never mentions it, and a substitution left unresolved would hide
 * whatever it stands for.
 */
@Tag("unit")
class ShippedConfigurationTest {

    @BeforeAll
    static void initInstructions() {
        // The policy manager sizes its lookup by the registered opcodes.
        Instruction.init();
    }

    /**
     * Builds every thermodynamic policy the shipped configuration names. A policy rejects an
     * option key it cannot place, so a stale or misspelled key fails here rather than when
     * somebody starts a node.
     */
    @Test
    void theShippedConfigurationBuildsItsThermodynamicPolicies() {
        Config runtimeConfig = shippedConfig()
                .getConfig("pipeline.services.simulation-engine.options.runtime");

        assertThatCode(() -> new ThermodynamicPolicyManager(
                runtimeConfig.hasPath("thermodynamics")
                        ? runtimeConfig.getConfig("thermodynamics")
                        : ConfigFactory.empty()))
                .doesNotThrowAnyException();
    }

    /** The shipped configuration, resolved through the loader a node uses. */
    private static Config shippedConfig() {
        return ConfigLoader.resolve(new File("config/evochora.conf"), (level, message) -> { });
    }
}
