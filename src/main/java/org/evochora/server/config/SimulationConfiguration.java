package org.evochora.server.config;

import java.util.List;
import java.util.Map;

/**
 * Holds simulation configuration loaded from JSON.
 */
public final class SimulationConfiguration {

    public static final class EnvironmentConfig {
        public int[] shape;
        public boolean toroidal;
    }

    public static final class PlacementConfig {
        public String strategy; // e.g., "fixed" (only supported for now)
        public int[][] positions; // array of coordinates
    }

    public static final class OrganismDefinition {
        public String id;
        public String program; // resource path relative to org/evochora/organism/
        public int initialEnergy;
        public PlacementConfig placement;
    }

    public static final class EnergyStrategyConfig {
        public String type;
        public Map<String, Object> params;
    }

    public static final class WebConfig {
        public Integer port; // default 7070 if null
        public String dbFile; // optional override for view mode
    }

    public EnvironmentConfig environment;
    public OrganismDefinition[] organisms;
    public List<EnergyStrategyConfig> energyStrategies;
    public Long seed;
    public WebConfig web;

    public int getDimensions() {
        return environment != null && environment.shape != null ? environment.shape.length : 2;
    }
}


