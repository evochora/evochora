package org.evochora.runtime.worldgen;

import java.util.Map;
import org.evochora.runtime.internal.services.IRandomProvider;

@FunctionalInterface
public interface IEnergyStrategyCreator {
    IEnergyDistributionCreator create(Map<String, Object> params, IRandomProvider randomProvider);
}