package org.evochora.server.config;

import com.google.gson.Gson;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;

public class ConfigLoader {
    /**
     * Loads a simulation configuration from a given file path.
     * @param path The path to the JSON configuration file.
     * @return The loaded SimulationConfiguration object.
     * @throws IOException If the file cannot be read.
     */
    public static SimulationConfiguration loadConfiguration(String path) throws IOException {
        Gson gson = new Gson();
        try (Reader reader = new FileReader(path)) {
            return gson.fromJson(reader, SimulationConfiguration.class);
        }
    }
}
