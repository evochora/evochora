package org.evochora.datapipeline.storage.impl.h2;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.storage.api.indexer.model.EnvironmentState;
import org.evochora.datapipeline.storage.api.indexer.model.Position;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for H2SimulationRepository Web Console functionality.
 */
@Tag("unit")
class H2SimulationRepositoryWebConsoleTest {

    private H2SimulationRepository repository;
    private Config testConfig;

    @BeforeEach
    void setUp() {
        // Set logger levels to WARN for services - only WARN and ERROR should be shown
        System.setProperty("org.evochora.datapipeline.storage.impl.h2.H2SimulationRepository", "WARN");
        
        // Create test configuration with Web Console enabled
        testConfig = ConfigFactory.parseString("""
            jdbcUrl: "jdbc:h2:mem:testdb_webconsole;DB_CLOSE_DELAY=-1"
            user: "sa"
            password: ""
            initializeSchema: true
            webConsole {
              enabled: true
              port: 8083
              allowOthers: false
              webSSL: false
              webPath: "/test-console"
              browser: false
            }
            """);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (repository != null) {
            repository.close();
        }
    }

    @Test
    void testWebConsoleConfiguration() {
        repository = new H2SimulationRepository(testConfig);
        
        // Test that repository can be created with Web Console config
        assertNotNull(repository);
    }

    @Test
    void testWebConsoleInitialization() {
        repository = new H2SimulationRepository(testConfig);
        
        // Initialize repository (this should start Web Console)
        repository.initialize(2, "test-simulation-run");
        
        // Test that we can write data (Web Console should not interfere)
        EnvironmentState testState = new EnvironmentState(
            1L, new Position(new int[]{0, 0}), "test", 100, 123L
        );
        
        assertDoesNotThrow(() -> {
            repository.writeEnvironmentStates(List.of(testState));
        });
    }

    @Test
    void testWebConsoleDisabled() {
        Config disabledConfig = ConfigFactory.parseString("""
            jdbcUrl: "jdbc:h2:mem:testdb_disabled;DB_CLOSE_DELAY=-1"
            user: "sa"
            password: ""
            initializeSchema: true
            webConsole {
              enabled: false
            }
            """);
        
        repository = new H2SimulationRepository(disabledConfig);
        repository.initialize(2, "test-simulation-run");
        
        // Should work normally without Web Console
        EnvironmentState testState = new EnvironmentState(
            1L, new Position(new int[]{0, 0}), "test", 100, 123L
        );
        
        assertDoesNotThrow(() -> {
            repository.writeEnvironmentStates(List.of(testState));
        });
    }

    @Test
    void testWebConsoleWithCustomSettings() {
        Config customConfig = ConfigFactory.parseString("""
            jdbcUrl: "jdbc:h2:mem:testdb_custom;DB_CLOSE_DELAY=-1"
            user: "sa"
            password: ""
            initializeSchema: true
            webConsole {
              enabled: true
              port: 8084
              allowOthers: true
              webSSL: false
              webPath: "/custom-path"
              webAdminPassword: "test123"
              browser: false
            }
            """);
        
        repository = new H2SimulationRepository(customConfig);
        
        // Should initialize without errors
        assertDoesNotThrow(() -> {
            repository.initialize(2, "test-simulation-run");
        });
    }
}
