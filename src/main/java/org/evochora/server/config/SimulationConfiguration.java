package org.evochora.server.config;

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

    public EnvironmentConfig environment;
    public OrganismDefinition[] organisms;

    public int getDimensions() {
        return environment != null && environment.shape != null ? environment.shape.length : 2;
    }
}


