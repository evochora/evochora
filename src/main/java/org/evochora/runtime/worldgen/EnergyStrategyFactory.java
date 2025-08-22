package org.evochora.runtime.worldgen;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.evochora.runtime.internal.services.IRandomProvider;

public class EnergyStrategyFactory {

    private static final Map<String, IEnergyStrategyCreator> registry = new HashMap<>();

    static {
        register("solar", (params, rngProvider) -> {
            double probability = ((Number) params.getOrDefault("probability", 0.001)).doubleValue();
            int amount = ((Number) params.getOrDefault("amount", 50)).intValue();
            int safetyRadius = ((Number) params.getOrDefault("safetyRadius", 2)).intValue();
            return new SolarRadiationCreator(rngProvider, probability, amount, safetyRadius);
        });

        register("geyser", (params, rngProvider) -> {
            int count = ((Number) params.getOrDefault("count", 5)).intValue();
            int interval = ((Number) params.getOrDefault("interval", 100)).intValue();
            int amount = ((Number) params.getOrDefault("amount", 200)).intValue();
            int safetyRadius = ((Number) params.getOrDefault("safetyRadius", 2)).intValue();
            return new GeyserCreator(rngProvider, count, interval, amount, safetyRadius);
        });
    }

    public static void register(String type, IEnergyStrategyCreator creator) {
        registry.put(type.toLowerCase(), creator);
    }

    public static IEnergyDistributionCreator create(String type, Map<String, Object> params, IRandomProvider rngProvider) {
        Objects.requireNonNull(type, "Strategy type cannot be null.");
        IEnergyStrategyCreator creator = registry.get(type.toLowerCase());
        if (creator == null) {
            throw new IllegalArgumentException("Unknown energy strategy type: " + type);
        }
        return creator.create(params != null ? params : Map.of(), rngProvider);
    }
}