package org.evochora.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads the simulation configuration from JSON.
 */
public final class ConfigLoader {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_RESOURCE_PATH = "org/evochora/config/config.json";

    private ConfigLoader() {}

    public static SimulationConfiguration loadDefault() {
        try (InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream(DEFAULT_RESOURCE_PATH)) {
            if (is != null) {
                return OBJECT_MAPPER.readValue(is, SimulationConfiguration.class);
            }
        } catch (Exception ignore) {}
        // Fallback default
        SimulationConfiguration cfg = new SimulationConfiguration();
        cfg.environment = new SimulationConfiguration.EnvironmentConfig();
        cfg.environment.shape = new int[]{120, 80};
        cfg.environment.toroidal = true;
        return cfg;
    }

    public static SimulationConfiguration loadFromFile(Path file) throws Exception {
        try (InputStream is = Files.newInputStream(file)) {
            return OBJECT_MAPPER.readValue(is, SimulationConfiguration.class);
        }
    }
}


