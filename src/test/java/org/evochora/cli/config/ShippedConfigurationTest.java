package org.evochora.cli.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.File;
import java.util.List;

import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;
import org.evochora.runtime.worldgen.GeneInsertionPlugin;
import org.evochora.runtime.worldgen.GeneSubstitutionPlugin;
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

    /**
     * Builds the mutation plugins the shipped configuration names, with the instruction weights
     * they refer to. A plugin rejects a key it cannot place and weights it cannot read, so a stale
     * or misspelled setting fails here rather than when somebody starts a node.
     */
    @Test
    void theShippedConfigurationBuildsItsMutationPlugins() {
        List<? extends Config> plugins = shippedConfig()
                .getConfigList("pipeline.services.simulation-engine.options.plugins");
        int built = 0;
        for (Config plugin : plugins) {
            String className = plugin.getString("className");
            Config options = plugin.getConfig("options");
            if (className.equals(GeneInsertionPlugin.class.getName())) {
                assertThatCode(() -> new GeneInsertionPlugin(new SeededRandomProvider(1), options))
                        .doesNotThrowAnyException();
                built++;
            } else if (className.equals(GeneSubstitutionPlugin.class.getName())) {
                assertThatCode(() -> new GeneSubstitutionPlugin(new SeededRandomProvider(1), options))
                        .doesNotThrowAnyException();
                built++;
            }
        }
        assertThat(built).as("insertion and substitution are configured").isEqualTo(2);
    }

    /** The shipped configuration, resolved through the loader a node uses. */
    private static Config shippedConfig() {
        return ConfigLoader.resolve(new File("config/evochora.conf"), (level, message) -> { });
    }
}
