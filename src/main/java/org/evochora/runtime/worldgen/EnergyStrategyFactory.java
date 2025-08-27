package org.evochora.runtime.worldgen;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.evochora.runtime.internal.services.IRandomProvider;

/**
 * A factory for creating energy distribution strategies.
 * It uses a registry to store different types of strategies.
 */
public class EnergyStrategyFactory {

    private static final Map<String, IEnergyStrategyCreator> registry = new HashMap<>();

    static {
        register("solar", (params, rngProvider) -> {
            double probability = ((Number) params.getOrDefault("probability", 0.001)).doubleValue();
            int amount = ((Number) params.getOrDefault("amount", 50)).intValue();
            int safetyRadius = ((Number) params.getOrDefault("safetyRadius", 2)).intValue();
            int executionsPerTick = ((Number) params.getOrDefault("executionsPerTick", 1)).intValue();
            return new SolarRadiationCreator(rngProvider, probability, amount, safetyRadius, executionsPerTick);
        });

        register("geyser", (params, rngProvider) -> {
            int count = ((Number) params.getOrDefault("count", 5)).intValue();
            int interval = ((Number) params.getOrDefault("interval", 100)).intValue();
            int amount = ((Number) params.getOrDefault("amount", 200)).intValue();
            int safetyRadius = ((Number) params.getOrDefault("safetyRadius", 2)).intValue();
            return new GeyserCreator(rngProvider, count, interval, amount, safetyRadius);
        });
    }

    /**
     * Registers a new energy strategy creator.
     * @param type The type of the strategy.
     * @param creator The creator for the strategy.
     */
    public static void register(String type, IEnergyStrategyCreator creator) {
        registry.put(type.toLowerCase(), creator);
    }

    /**
     * Creates a new energy distribution creator.
     * @param type The type of the strategy to create.
     * @param params The parameters for the strategy.
     * @param rngProvider The random number provider.
     * @return The created energy distribution creator.
     * @throws IllegalArgumentException if the strategy type is unknown.
     */
    public static IEnergyDistributionCreator create(String type, Map<String, Object> params, IRandomProvider rngProvider) {
        Objects.requireNonNull(type, "Strategy type cannot be null.");
        IEnergyStrategyCreator creator = registry.get(type.toLowerCase());
        if (creator == null) {
            throw new IllegalArgumentException("Unknown energy strategy type: " + type);
        }
        return creator.create(params != null ? params : Map.of(), rngProvider);
    }
}