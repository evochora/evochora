package org.evochora.server.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.JsonParseException;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

/**
 * Loads the simulation configuration from JSONC (JSON with Comments).
 * Supports both // and /* *\/ style comments.
 */
public final class ConfigLoader {
    private static final Gson GSON = new GsonBuilder()
        .setLenient() // Allow comments and other JSON5 features
        .create();
    private static final String DEFAULT_RESOURCE_PATH = "org/evochora/config/config.jsonc";

    private ConfigLoader() {}
    
    /**
     * Formats a JSON parsing exception into a user-friendly error message.
     * 
     * @param e the exception
     * @param configPath the path to the config file
     * @return a formatted error message
     */
    private static String formatJsonError(Exception e, String configPath) {
        if (e instanceof JsonSyntaxException) {
            JsonSyntaxException jsonEx = (JsonSyntaxException) e;
            String message = jsonEx.getMessage();
            
            StringBuilder sb = new StringBuilder();
            sb.append("Invalid JSON in '").append(configPath).append("'");
            
            // Gson provides good error messages with line numbers
            if (message != null) {
                // Extract line number from Gson error message
                if (message.contains("at line")) {
                    int lineStart = message.indexOf("at line") + 7;
                    int lineEnd = message.indexOf("column", lineStart);
                    if (lineEnd > lineStart) {
                        String lineInfo = message.substring(lineStart, lineEnd).trim();
                        sb.append(" at line ").append(lineInfo);
                    }
                }
                
                // Extract the actual error description
                if (message.contains("Expected")) {
                    int expectedStart = message.indexOf("Expected");
                    int pathStart = message.indexOf("at line");
                    if (pathStart > expectedStart) {
                        String errorDesc = message.substring(expectedStart, pathStart).trim();
                        sb.append(": ").append(errorDesc);
                    }
                }
            }
            
            return sb.toString();
        } else {
            return "Failed to load '" + configPath + "': " + e.getMessage();
        }
    }


    public static SimulationConfiguration loadDefault() {
        try (InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream(DEFAULT_RESOURCE_PATH)) {
            if (is != null) {
                // Read the JSONC content
                String jsoncContent = new String(is.readAllBytes());
                // Parse the JSONC directly with Gson (supports comments natively)
                return GSON.fromJson(jsoncContent, SimulationConfiguration.class);
            } else {
                throw new RuntimeException("Default config file not found in resources: " + DEFAULT_RESOURCE_PATH);
            }
        } catch (Exception e) {
            throw new RuntimeException(formatJsonError(e, DEFAULT_RESOURCE_PATH));
        }
    }
    
    /**
     * Loads the default configuration with fallback for missing options.
     * This method is used internally when some configuration options are missing.
     */
    public static SimulationConfiguration loadDefaultWithFallback() {
        try (InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream(DEFAULT_RESOURCE_PATH)) {
            if (is != null) {
                // Read the JSONC content
                String jsoncContent = new String(is.readAllBytes());
                // Parse the JSONC directly with Gson (supports comments natively)
                return GSON.fromJson(jsoncContent, SimulationConfiguration.class);
            }
        } catch (Exception ignore) {}
        
        // Fallback default with new structure
        SimulationConfiguration cfg = new SimulationConfiguration();
        
        // Create simulation config
        SimulationConfiguration.SimulationConfig simConfig = new SimulationConfiguration.SimulationConfig();
        simConfig.environment = new SimulationConfiguration.EnvironmentConfig();
        simConfig.environment.shape = new int[]{120, 80};
        simConfig.environment.toroidal = true;
        simConfig.seed = 123456789L;
        cfg.simulation = simConfig;
        
        // Create pipeline config
        SimulationConfiguration.PipelineConfig pipelineConfig = new SimulationConfiguration.PipelineConfig();
        
        SimulationConfiguration.SimulationServiceConfig simService = new SimulationConfiguration.SimulationServiceConfig();
        simService.autoStart = true;
        simService.outputPath = "runs/";
        pipelineConfig.simulation = simService;
        
        SimulationConfiguration.IndexerServiceConfig indexerService = new SimulationConfiguration.IndexerServiceConfig();
        indexerService.autoStart = true;
        indexerService.inputPath = "runs/";
        indexerService.outputPath = "runs/";
        indexerService.batchSize = 1000;
        pipelineConfig.indexer = indexerService;
        
        SimulationConfiguration.PersistenceServiceConfig persistenceService = new SimulationConfiguration.PersistenceServiceConfig();
        persistenceService.autoStart = true;
        persistenceService.batchSize = 1000;
        pipelineConfig.persistence = persistenceService;
        
        SimulationConfiguration.ServerServiceConfig serverService = new SimulationConfiguration.ServerServiceConfig();
        serverService.autoStart = true;
        serverService.inputPath = "runs/";
        serverService.port = 7070;
        serverService.host = "localhost";
        pipelineConfig.server = serverService;
        
        // Create logging config for fallback
        SimulationConfiguration.LoggingConfig loggingConfig = new SimulationConfiguration.LoggingConfig();
        loggingConfig.defaultLogLevel = "INFO";
        loggingConfig.logLevels = new java.util.HashMap<>();
        loggingConfig.logLevels.put("org.evochora.server.CommandLineInterface", "INFO");
        loggingConfig.logLevels.put("org.evochora.server.ServiceManager", "INFO");
        pipelineConfig.logging = loggingConfig;
        
        cfg.pipeline = pipelineConfig;
        
        return cfg;
    }

    public static SimulationConfiguration loadFromFile(Path file) throws Exception {
        try (InputStream is = Files.newInputStream(file)) {
            // Read the JSONC content
            String jsoncContent = new String(is.readAllBytes());
            // Parse the JSONC directly with Gson (supports comments natively)
            return GSON.fromJson(jsoncContent, SimulationConfiguration.class);
        } catch (Exception e) {
            throw new Exception(formatJsonError(e, file.toString()));
        }
    }
}


