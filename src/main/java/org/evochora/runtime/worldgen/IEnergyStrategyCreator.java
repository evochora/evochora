package org.evochora.runtime.worldgen;

import java.util.Map;
import org.evochora.runtime.worldgen.IEnergyDistributionStrategy; // KORREKTUR

@FunctionalInterface
public interface IEnergyStrategyCreator {
    IEnergyDistributionStrategy create(Map<String, Object> params);
}