package org.evochora.runtime.spi.thermodynamics;

import com.typesafe.config.Config;

/**
 * Service Provider Interface for defining thermodynamic policies.
 * <p>
 * Implementations of this interface calculate the energy cost and entropy
 * delta for instruction execution based on the provided {@link ThermodynamicContext}.
 * Policies are loaded and configured via HOCON configuration files.
 */
public interface IThermodynamicPolicy {

    /**
     * Result of thermodynamic calculation containing both energy cost and entropy delta.
     * 
     * @param energyCost The energy cost (positive = consumption, negative = gain)
     * @param entropyDelta The entropy delta (positive = generation, negative = dissipation)
     */
    public record Thermodynamics(int energyCost, int entropyDelta) {}

    /**
     * Initializes the policy with its specific configuration object.
     * This method is called by the {@code ThermodynamicPolicyManager} immediately
     * after the policy is instantiated.
     *
     * @param options The HOCON configuration object for this policy instance.
     *                This can be an empty Config if no options are provided in the
     *                main configuration file.
     */
    void initialize(Config options);

    /**
     * Calculates both energy cost and entropy delta in a single call for optimal performance.
     * This is the preferred method for performance-critical code paths.
     *
     * @param context The {@link ThermodynamicContext} containing all relevant runtime information.
     * @return A {@link Thermodynamics} record containing both energy cost and entropy delta.
     */
    default Thermodynamics getThermodynamics(ThermodynamicContext context) {
        return new Thermodynamics(getEnergyCost(context), getEntropyDelta(context));
    }

    /**
     * Calculates the energy cost for the instruction execution described in the context.
     *
     * @param context The {@link ThermodynamicContext} containing all relevant runtime information.
     * @return The calculated energy cost. A positive value represents energy consumption,
     *         while a negative value represents an energy gain (e.g., from PEEK on an ENERGY cell).
     */
    int getEnergyCost(ThermodynamicContext context);

    /**
     * Calculates the change in entropy for the instruction execution described in the context.
     *
     * @param context The {@link ThermodynamicContext} containing all relevant runtime information.
     * @return The change in entropy (delta). A positive value represents entropy generation,
     *         while a negative value represents entropy dissipation (e.g., from POKE).
     */
    int getEntropyDelta(ThermodynamicContext context);
}
