package org.evochora.test.utils;

import java.util.Map;

import org.evochora.runtime.Simulation;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Test utilities for creating Simulation instances with standard test configuration.
 * <p>
 * This class provides factory methods that create Simulation instances with
 * default thermodynamic policies and organism limits suitable for testing.
 * Tests should use these methods instead of directly calling the Simulation constructor
 * to ensure they work with the configurable policy system.
 * <p>
 * The thermodynamic configuration is defined inline to ensure test isolation from
 * application configuration files (reference.conf, evochora.conf).
 */
public class SimulationTestUtils {

    /** Default maximum energy for test organisms. */
    private static final int DEFAULT_MAX_ENERGY = 32767;

    /** Default maximum entropy for test organisms. */
    private static final int DEFAULT_MAX_ENTROPY = 8191;

    /** Default energy penalty for instruction failures. */
    private static final int DEFAULT_ERROR_PENALTY_COST = 10;

    /**
     * Standard thermodynamic configuration for tests.
     * <p>
     * This configuration is intentionally inline (not loaded from files) to ensure
     * tests are isolated from application configuration changes. The values match
     * the production configuration in evochora.conf for consistency.
     */
    private static final String THERMODYNAMIC_CONFIG = """
            default {
              className = "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy"
              options {
                base-energy = 1
                base-entropy = 1
              }
            }
            overrides {
              instructions {
                "PEEK, PEKI, PEKS, POKE, POKI, POKS, PPKR, PPKI, PPKS" {
                  className = "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy"
                  options {
                    base-energy = 0
                    base-entropy = 0
                    read-rules {
                      own:       { _default: { energy = 0, entropy = 0 }, ENERGY: { energy = 0, entropy = 0 }, STRUCTURE: { energy = 0, entropy = 0 }, CODE: { energy = 0, entropy = 0 }, DATA: { energy = 0, entropy = 0 } }
                      foreign:   { _default: { energy = 5, entropy = 5 }, ENERGY: { energy-permille = -1000, entropy = 0 }, STRUCTURE: { energy-permille = 1000, entropy-permille = 1000 }, CODE: { energy = 5, entropy = 5 }, DATA: { energy = 5, entropy = 5 } }
                      unowned:   { _default: { energy = 0, entropy = 0 }, ENERGY: { energy-permille = -1000, entropy = 0 }, STRUCTURE: { energy = 0, entropy = 0 }, CODE: { energy = 0, entropy = 0 }, DATA: { energy = 0, entropy = 0 } }
                    }
                    write-rules {
                      ENERGY {
                        energy-permille = 1000
                        entropy-permille = 1000
                      }
                      STRUCTURE {
                        energy-permille = 1000
                        entropy-permille = 1000
                      }
                      CODE {
                        energy = 6
                        entropy-permille = -1000
                      }
                      DATA {
                        energy = 6
                        entropy-permille = -1000
                      }
                    }
                  }
                }
              }
              families {}
            }
            """;

    private SimulationTestUtils() {
        // Utility class - prevent instantiation
    }

    /**
     * Creates a Simulation with standard test configuration.
     * <p>
     * Uses default values:
     * <ul>
     *   <li>max-energy: 32767</li>
     *   <li>max-entropy: 8191</li>
     *   <li>error-penalty-cost: 10</li>
     *   <li>Default policy: UniversalThermodynamicPolicy with base-energy=1, base-entropy=1</li>
     * </ul>
     *
     * @param environment The environment for the simulation.
     * @return A Simulation instance configured for testing.
     */
    public static Simulation createSimulation(Environment environment) {
        return createSimulation(environment, DEFAULT_MAX_ENERGY, DEFAULT_MAX_ENTROPY, DEFAULT_ERROR_PENALTY_COST);
    }

    /**
     * Creates a Simulation with custom organism configuration.
     * <p>
     * Uses the standard thermodynamic policy but allows custom organism limits.
     *
     * @param environment The environment for the simulation.
     * @param maxEnergy Maximum energy for organisms.
     * @param maxEntropy Maximum entropy for organisms.
     * @param errorPenaltyCost Energy penalty for instruction failures.
     * @return A Simulation instance configured for testing.
     */
    public static Simulation createSimulation(Environment environment, int maxEnergy, int maxEntropy, int errorPenaltyCost) {
        Config organismConfig = ConfigFactory.parseMap(Map.of(
            "max-energy", maxEnergy,
            "max-entropy", maxEntropy,
            "error-penalty-cost", errorPenaltyCost
        ));

        Config thermoConfig = ConfigFactory.parseString(THERMODYNAMIC_CONFIG);
        ThermodynamicPolicyManager policyManager = new ThermodynamicPolicyManager(thermoConfig);

        return new Simulation(environment, policyManager, organismConfig);
    }
}
