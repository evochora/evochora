package org.evochora.runtime.worldgen;

import java.util.Map;

@FunctionalInterface
public interface IEnergyStrategyCreator {
    IEnergyDistributionCreator create(Map<String, Object> params);
}