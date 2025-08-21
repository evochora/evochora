package org.evochora.server.config;

/**
 * Holds simulation configuration loaded from JSON.
 */
public final class SimulationConfiguration {

    public static final class EnvironmentConfig {
        public int[] shape;
        public boolean toroidal;
    }

    public EnvironmentConfig environment;

    public int getDimensions() {
        return environment != null && environment.shape != null ? environment.shape.length : 2;
    }
}


