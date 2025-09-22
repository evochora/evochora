package org.evochora.datapipeline.storage.impl.h2;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.storage.api.indexer.model.EnvironmentState;
import org.evochora.datapipeline.storage.api.indexer.model.Position;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for H2SimulationRepository.
 * 
 * <p>These tests use an in-memory H2 database and do not depend on evochora.conf.
 * All tests are tagged as "unit" since they run fast and use no I/O resources.</p>
 */
@Tag("unit")
class H2SimulationRepositoryTest {
    
    private H2SimulationRepository repository;
    private Config testConfig;
    
    @BeforeEach
    void setUp() {
        // Set logger levels to WARN for services - only WARN and ERROR should be shown
        System.setProperty("org.evochora.datapipeline.storage.impl.h2.H2SimulationRepository", "WARN");
        
        // Create test configuration with unique in-memory H2 database for each test
        String uniqueDbName = "testdb_" + System.currentTimeMillis() + "_" + Thread.currentThread().getId();
        testConfig = ConfigFactory.parseString(String.format("""
            jdbcUrl: "jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1"
            user: "sa"
            password: ""
            initializeSchema: true
            """, uniqueDbName));
        
        repository = new H2SimulationRepository(testConfig);
    }
    
    @AfterEach
    void tearDown() throws Exception {
        if (repository != null) {
            repository.close();
        }
    }
    
    @Test
    void testInitializeWithValidDimensions() {
        // Given
        int dimensions = 2;
        
        // When & Then
        assertDoesNotThrow(() -> repository.initialize(dimensions, "test-run-123"));
    }
    
    @Test
    void testInitializeWithInvalidDimensions() {
        // Test zero dimensions
        assertThrows(IllegalArgumentException.class, () -> repository.initialize(0, "test-run"));
        
        // Test negative dimensions
        assertThrows(IllegalArgumentException.class, () -> repository.initialize(-1, "test-run"));
    }
    
    @Test
    void testWriteEnvironmentStatesWithoutInitialization() {
        // Given
        List<EnvironmentState> states = List.of(
            new EnvironmentState(1L, new Position(new int[]{1, 2}), "ENERGY", 100, 1L)
        );
        
        // When & Then
        assertThrows(IllegalStateException.class, () -> repository.writeEnvironmentStates(states));
    }
    
    @Test
    void testWriteEnvironmentStatesWithDimensionMismatch() throws Exception {
        // Given
        repository.initialize(2, "test-run-" + System.currentTimeMillis()); // 2D environment
        List<EnvironmentState> states = List.of(
            new EnvironmentState(1L, new Position(new int[]{1, 2, 3}), "ENERGY", 100, 1L) // 3D position
        );
        
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> repository.writeEnvironmentStates(states));
    }
    
    @Test
    void testWriteEnvironmentStatesWithNullList() throws Exception {
        // Given
        repository.initialize(2, "test-run-" + System.currentTimeMillis());
        
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> repository.writeEnvironmentStates(null));
    }
    
    @Test
    void testWriteEnvironmentStatesWithEmptyList() throws Exception {
        // Given
        repository.initialize(2, "test-run-" + System.currentTimeMillis());
        List<EnvironmentState> emptyStates = List.of();
        
        // When & Then
        assertDoesNotThrow(() -> repository.writeEnvironmentStates(emptyStates));
    }
    
    @Test
    void testWriteEnvironmentStatesSuccessfully() throws Exception {
        // Given
        repository.initialize(2, "test-run-" + System.currentTimeMillis());
        List<EnvironmentState> states = List.of(
            new EnvironmentState(1L, new Position(new int[]{1, 2}), "ENERGY", 100, 1L),
            new EnvironmentState(1L, new Position(new int[]{3, 4}), "FOOD", 50, 2L),
            new EnvironmentState(2L, new Position(new int[]{5, 6}), "ENERGY", 75, 0L)
        );
        
        // When
        repository.writeEnvironmentStates(states);
        
        // Then
        verifyDataInDatabase(states);
    }
    
    @Test
    void testWriteEnvironmentStatesAtomicOperation() throws Exception {
        // Given
        repository.initialize(2, "test-run-" + System.currentTimeMillis());
        List<EnvironmentState> validStates = List.of(
            new EnvironmentState(1L, new Position(new int[]{1, 2}), "ENERGY", 100, 1L)
        );
        List<EnvironmentState> invalidStates = List.of(
            new EnvironmentState(2L, new Position(new int[]{3, 4}), "FOOD", 50, 2L),
            new EnvironmentState(3L, new Position(new int[]{5, 6, 7}), "ENERGY", 75, 3L) // Wrong dimensions
        );
        
        // When - write valid states first
        repository.writeEnvironmentStates(validStates);
        
        // Then - verify valid states are written
        verifyDataInDatabase(validStates);
        
        // When - try to write invalid states (should fail and rollback)
        assertThrows(IllegalArgumentException.class, () -> repository.writeEnvironmentStates(invalidStates));
        
        // Then - verify only valid states remain (atomic operation)
        verifyDataInDatabase(validStates);
    }
    
    @Test
    void testWriteEnvironmentStatesWithDifferentDimensions() throws Exception {
        // Given
        repository.initialize(3, "test-simulation-run"); // 3D environment
        List<EnvironmentState> states = List.of(
            new EnvironmentState(1L, new Position(new int[]{1, 2, 3}), "ENERGY", 100, 1L),
            new EnvironmentState(1L, new Position(new int[]{4, 5, 6}), "FOOD", 50, 2L)
        );
        
        // When
        repository.writeEnvironmentStates(states);
        
        // Then
        verifyDataInDatabase(states);
    }
    
    @Test
    void testTableCreationWithCorrectColumns() throws Exception {
        // Given
        int dimensions = 3;
        
        // When
        repository.initialize(dimensions, "test-simulation-run");
        
        // Then
        verifyTableStructure(dimensions);
    }
    
    /**
     * Verifies that the data was correctly written to the database.
     */
    private void verifyDataInDatabase(List<EnvironmentState> expectedStates) throws Exception {
        String jdbcUrl = testConfig.getString("jdbcUrl");
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM environment_state ORDER BY tick, pos_0, pos_1")) {
            
            int count = 0;
            while (rs.next()) {
                assertTrue(count < expectedStates.size(), "More rows in database than expected");
                
                EnvironmentState expected = expectedStates.get(count);
                assertEquals(expected.tick(), rs.getLong("tick"));
                assertEquals(expected.moleculeType(), rs.getString("molecule_type"));
                assertEquals(expected.moleculeValue(), rs.getInt("molecule_value"));
                assertEquals(expected.owner(), rs.getLong("owner"));
                
                // Verify position coordinates
                int[] expectedPos = expected.position().coordinates();
                for (int i = 0; i < expectedPos.length; i++) {
                    assertEquals(expectedPos[i], rs.getInt("pos_" + i));
                }
                
                count++;
            }
            
            assertEquals(expectedStates.size(), count, "Number of rows in database doesn't match expected");
        }
    }
    
    /**
     * Verifies that the table was created with the correct structure.
     */
    private void verifyTableStructure(int expectedDimensions) throws Exception {
        String jdbcUrl = testConfig.getString("jdbcUrl");
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'ENVIRONMENT_STATE' ORDER BY ORDINAL_POSITION")) {
            
            // Expected columns: tick, molecule_type, molecule_value, owner, pos_0, pos_1, ..., pos_n
            String[] expectedColumns = {"TICK", "MOLECULE_TYPE", "MOLECULE_VALUE", "OWNER"};
            for (int i = 0; i < expectedDimensions; i++) {
                expectedColumns = Arrays.copyOf(expectedColumns, expectedColumns.length + 1);
                expectedColumns[expectedColumns.length - 1] = "POS_" + i;
            }
            
            int columnIndex = 0;
            while (rs.next()) {
                assertTrue(columnIndex < expectedColumns.length, "More columns than expected");
                assertEquals(expectedColumns[columnIndex], rs.getString("COLUMN_NAME"));
                columnIndex++;
            }
            
            assertEquals(expectedColumns.length, columnIndex, "Number of columns doesn't match expected");
        }
    }
}
