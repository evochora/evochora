package org.evochora.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.BufferedReader;
import java.io.StringReader;
import java.io.IOException;

/**
 * Loads the simulation configuration from JSONC (JSON with Comments).
 * Supports both // and /* *\/ style comments.
 */
public final class ConfigLoader {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_RESOURCE_PATH = "org/evochora/config/config.jsonc";

    private ConfigLoader() {}

    /**
     * Strips comments from JSONC content to make it valid JSON.
     * Supports both // and /* *\/ style comments.
     */
    private static String stripComments(String jsoncContent) throws IOException {
        StringBuilder result = new StringBuilder();
        BufferedReader reader = new BufferedReader(new StringReader(jsoncContent));
        String line;
        boolean inMultiLineComment = false;
        
        while ((line = reader.readLine()) != null) {
            String strippedLine = line;
            
            if (inMultiLineComment) {
                // Check if multi-line comment ends on this line
                int endComment = strippedLine.indexOf("*/");
                if (endComment != -1) {
                    inMultiLineComment = false;
                    strippedLine = strippedLine.substring(endComment + 2);
                } else {
                    // Still in multi-line comment, skip this line
                    continue;
                }
            }
            
            // Check for start of multi-line comment
            int startComment = strippedLine.indexOf("/*");
            if (startComment != -1) {
                inMultiLineComment = true;
                strippedLine = strippedLine.substring(0, startComment);
                
                // Check if comment ends on same line
                int endComment = strippedLine.indexOf("*/");
                if (endComment != -1) {
                    inMultiLineComment = false;
                    strippedLine = strippedLine.substring(0, startComment) + strippedLine.substring(endComment + 2);
                }
            }
            
            // Remove single-line comments
            int singleLineComment = strippedLine.indexOf("//");
            if (singleLineComment != -1) {
                strippedLine = strippedLine.substring(0, singleLineComment);
            }
            
            // Only add non-empty lines
            if (!strippedLine.trim().isEmpty()) {
                result.append(strippedLine).append("\n");
            }
        }
        
        return result.toString();
    }

    public static SimulationConfiguration loadDefault() {
        try (InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream(DEFAULT_RESOURCE_PATH)) {
            if (is != null) {
                // Read the JSONC content
                String jsoncContent = new String(is.readAllBytes());
                // Strip comments to get valid JSON
                String jsonContent = stripComments(jsoncContent);
                // Parse the JSON
                return OBJECT_MAPPER.readValue(jsonContent, SimulationConfiguration.class);
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
        
        cfg.pipeline = pipelineConfig;
        
        return cfg;
    }

    public static SimulationConfiguration loadFromFile(Path file) throws Exception {
        try (InputStream is = Files.newInputStream(file)) {
            // Read the JSONC content
            String jsoncContent = new String(is.readAllBytes());
            // Strip comments to get valid JSON
            String jsonContent = stripComments(jsoncContent);
            // Parse the JSON
            return OBJECT_MAPPER.readValue(jsonContent, SimulationConfiguration.class);
        }
    }
}


