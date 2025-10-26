package org.evochora.datapipeline.resources.database;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.EnvironmentConfig;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareMetadataWriter;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 error handling tests: Database connection failures
 */
@Tag("integration")  // Uses database for error simulation
class DatabaseReaderErrorHandlingTest {
    
    private H2Database database;
    
    @BeforeEach
    void setUp() {
        // Valid database setup
        Config config = ConfigFactory.parseString(
            "jdbcUrl = \"jdbc:h2:mem:test-errors-" + System.nanoTime() + ";MODE=PostgreSQL\"\n" +
            "maxPoolSize = 2\n"
        );
        database = new H2Database("test-db", config);
    }
    
    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }
    
    /**
     * Creates a test schema with metadata for realistic testing.
     */
    private void createTestSchema(String runId) throws SQLException {
        try (IResourceSchemaAwareMetadataWriter writer = (IResourceSchemaAwareMetadataWriter) database.getWrappedResource(
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
    }
    
    @Test
    void createReader_throwsOnInvalidRunId() {
        // Given: Invalid run-id (schema doesn't exist)
        String invalidRunId = "nonexistent_run";
        
        // When/Then: Should throw RuntimeException with SQLException cause
        RuntimeException exception = assertThrows(RuntimeException.class, () -> 
            database.createReader(invalidRunId)
        );
        
        assertTrue(exception.getCause() instanceof SQLException);
        assertTrue(exception.getMessage().contains("Failed to create reader"));
    }
    
    @Test
    void createReader_throwsOnNullRunId() {
        assertThrows(IllegalArgumentException.class, () -> 
            database.createReader(null)
        );
    }
    
    @Test
    void findLatestRunId_returnsNullOnEmptyDatabase() throws SQLException {
        // Given: Empty database (no schemas)
        
        // When: Find latest run ID
        String result = database.findLatestRunId();
        
        // Then: Should return null (no simulation runs found)
        assertNull(result);
    }
    
    @Test
    void findLatestRunId_returnsLatestRunId() throws SQLException {
        // Given: Database with test schemas
        createTestSchema("test_run_1");
        createTestSchema("test_run_2");
        
        // When: Find latest run ID
        String result = database.findLatestRunId();
        
        // Then: Should return one of the run IDs (order may vary)
        assertNotNull(result);
        assertTrue(result.equals("test_run_1") || result.equals("test_run_2"));
    }
    
    @Test
    void getMetadata_worksWithValidSchema() throws SQLException, org.evochora.datapipeline.api.resources.database.MetadataNotFoundException {
        // Given: Valid schema with metadata
        String runId = "test_run_valid";
        createTestSchema(runId);
        
        // When: Create reader and get metadata
        try (IDatabaseReader reader = database.createReader(runId)) {
            SimulationMetadata metadata = reader.getMetadata();
            
            // Then: Should return valid metadata
            assertNotNull(metadata);
            assertEquals(runId, metadata.getSimulationRunId());
        }
    }
    
    @Test
    void getMetadata_throwsOnInvalidRunId() throws SQLException {
        // Given: Invalid run-id (schema doesn't exist)
        String invalidRunId = "nonexistent_run";
        
        // When: Try to create reader for invalid run-id
        // Then: Should throw RuntimeException (schema doesn't exist)
        assertThrows(RuntimeException.class, () -> 
            database.createReader(invalidRunId)
        );
    }
    
    @Test
    void connectionLeak_preventedOnException() throws SQLException {
        // Given: Database with limited pool
        Config limitedConfig = ConfigFactory.parseString(
            "jdbcUrl = \"jdbc:h2:mem:test-leak-" + System.nanoTime() + ";MODE=PostgreSQL\"\n" +
            "maxPoolSize = 2\n"  // Small but not too limited
        );
        
        try (H2Database limitedDb = new H2Database("limited-db", limitedConfig)) {
            // Create a valid schema first
            createTestSchema(limitedDb, "valid_run");
            
            // When: Reader creation fails but connection is still returned
            assertThrows(RuntimeException.class, () -> 
                limitedDb.createReader("invalid_run")
            );
            
            // Then: Pool should still be usable (connection returned)
            // This test verifies that failed createReader() doesn't leak connections
            assertDoesNotThrow(() -> {
                try (IDatabaseReader reader = limitedDb.createReader("valid_run")) {
                    // Just create and immediately close to test pool health
                }
            });
        }
    }
    
    private void createTestSchema(H2Database db, String runId) throws SQLException {
        try (IResourceSchemaAwareMetadataWriter writer = (IResourceSchemaAwareMetadataWriter) db.getWrappedResource(
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
    }
    
    @Test
    void reader_close_preventsFurtherOperations() throws SQLException {
        // Given: Valid schema and reader
        String runId = "test_run_close";
        createTestSchema(runId);
        
        try (IDatabaseReader reader = database.createReader(runId)) {
            // When: Close the reader
            reader.close();
            
            // Then: Further operations should fail
            assertThrows(IllegalStateException.class, () -> 
                reader.getMetadata()
            );
            
            assertThrows(IllegalStateException.class, () -> 
                reader.hasMetadata()
            );
        }
    }
    
    @Test
    void reader_doubleClose_isSafe() throws SQLException {
        // Given: Valid schema and reader
        String runId = "test_run_double_close";
        createTestSchema(runId);
        
        try (IDatabaseReader reader = database.createReader(runId)) {
            // When: Close twice
            reader.close();
            reader.close();  // Should not throw
            
            // Then: Still should be safe
            assertDoesNotThrow(() -> reader.close());
        }
    }
}
