package org.evochora.datapipeline.resources.database;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.EnvironmentConfig;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.database.IMetadataReader;
import org.evochora.datapipeline.api.resources.database.IMetadataWriter;
import org.evochora.datapipeline.api.resources.database.MetadataNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link IMetadataReader#getRunIdInCurrentSchema()}.
 * <p>
 * Uses in-memory H2 database (no filesystem I/O) for fast test execution.
 * Verifies that run-id can be retrieved from metadata table without knowing the run-id in advance.
 */
@Tag("integration")
class MetadataReaderRunIdTest {
    
    private H2Database database;
    
    @BeforeEach
    void setUp() {
        // Use in-memory H2 database (AGENTS.md: "If database needed: use in-memory")
        Config config = ConfigFactory.parseString(
            "jdbcUrl = \"jdbc:h2:mem:test-runid-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL\"\n" +
            "maxPoolSize = 2\n" +
            "envStorageStrategy = \"single-blob\"\n"
        );
        
        database = new H2Database("test-db", config);
    }
    
    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }
    
    @Test
    void testGetRunIdInCurrentSchema_withExistingMetadata() throws Exception {
        String runId = "20251021-14302567-550e8400-e29b-41d4-a716-446655440000";
        
        // Write metadata
        try (IMetadataWriter writer = (IMetadataWriter) database.getWrappedResource(
                new ResourceContext("test", "meta-port", "db-meta-write", "test-db", Map.of()))) {
            
            writer.setSimulationRun(runId);
            
            SimulationMetadata metadata = SimulationMetadata.newBuilder()
                .setSimulationRunId(runId)
                .setStartTimeMs(System.currentTimeMillis())
                .setInitialSeed(12345L)
                .setSamplingInterval(1)
                .setEnvironment(EnvironmentConfig.newBuilder()
                    .setDimensions(2)
                    .addShape(100)
                    .addShape(100)
                    .addToroidal(true)
                    .addToroidal(true)
                    .build())
                .build();
            
            writer.insertMetadata(metadata);
        }
        
        // Read run-id from schema (without knowing run-id in advance)
        try (IMetadataReader reader = (IMetadataReader) database.getWrappedResource(
                new ResourceContext("test", "meta-port", "db-meta-read", "test-db", Map.of()))) {
            
            // Set schema directly (simulating HTTP API scenario where we know schema name)
            reader.setSimulationRun(runId);
            
            // Get run-id from metadata
            String retrievedRunId = reader.getRunIdInCurrentSchema();
            
            assertEquals(runId, retrievedRunId);
        }
    }
    
    @Test
    void testGetRunIdInCurrentSchema_withoutMetadata() throws Exception {
        String runId = "test-run-empty";
        
        // Try to read run-id from non-existent schema
        try (IMetadataReader reader = (IMetadataReader) database.getWrappedResource(
                new ResourceContext("test", "meta-port", "db-meta-read", "test-db", Map.of()))) {
            
            reader.setSimulationRun(runId);
            
            // Metadata doesn't exist yet (schema not created)
            assertThrows(MetadataNotFoundException.class, () -> {
                reader.getRunIdInCurrentSchema();
            });
        }
    }
    
    @Test
    void testGetRunIdInCurrentSchema_withDifferentRunIdFormats() throws Exception {
        // Test various run-id formats to ensure no parsing issues
        String[] runIds = {
            "20251021-14302567-550e8400-e29b-41d4-a716-446655440000",  // Standard format
            "test-run-123",                                             // Simple test format
            "run@2025#10-06!test$",                                     // Special characters
            "test_run_with_underscores"                                 // Underscores
        };
        
        for (String runId : runIds) {
            // Write metadata
            try (IMetadataWriter writer = (IMetadataWriter) database.getWrappedResource(
                    new ResourceContext("test", "meta-port", "db-meta-write", "test-db", Map.of()))) {
                
                writer.setSimulationRun(runId);
                
                SimulationMetadata metadata = SimulationMetadata.newBuilder()
                    .setSimulationRunId(runId)
                    .setStartTimeMs(System.currentTimeMillis())
                    .setInitialSeed(12345L)
                    .setSamplingInterval(1)
                    .setEnvironment(EnvironmentConfig.newBuilder()
                        .setDimensions(1)
                        .addShape(10)
                        .addToroidal(false)
                        .build())
                    .build();
                
                writer.insertMetadata(metadata);
            }
            
            // Verify run-id retrieval
            try (IMetadataReader reader = (IMetadataReader) database.getWrappedResource(
                    new ResourceContext("test", "meta-port", "db-meta-read", "test-db", Map.of()))) {
                
                reader.setSimulationRun(runId);
                String retrievedRunId = reader.getRunIdInCurrentSchema();
                
                assertEquals(runId, retrievedRunId, 
                    "Run-ID should be retrieved correctly regardless of format");
            }
        }
    }
}

