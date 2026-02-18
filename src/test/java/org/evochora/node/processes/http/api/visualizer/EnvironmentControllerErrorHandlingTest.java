package org.evochora.node.processes.http.api.visualizer;

import static io.restassured.RestAssured.given;

import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.datapipeline.resources.database.H2Database;
import org.evochora.junit.extensions.logging.ExpectLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.node.spi.ServiceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import io.javalin.Javalin;

import java.nio.file.Path;

/**
 * Phase 3 error handling tests: HTTP error responses and edge cases.
 * <p>
 * Tests various error conditions in the EnvironmentController:
 * - Invalid parameters (region, tick, runId)
 * - Empty database
 * - Database errors
 * - Connection pool exhaustion
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class EnvironmentControllerErrorHandlingTest {

    @TempDir
    Path tempChunkDir;

    private H2Database database;
    private Javalin app;
    private int port;

    @BeforeEach
    void setUp() {
        // In-memory H2 database
        Config dbConfig = ConfigFactory.parseString(
            "jdbcUrl = \"jdbc:h2:mem:test-http-errors-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL\"\n" +
            "username = \"sa\"\n" +
            "password = \"\"\n" +
            "maxPoolSize = 5\n" +
            "h2EnvironmentStrategy {\n" +
            "  className = \"org.evochora.datapipeline.resources.database.h2.RowPerChunkStrategy\"\n" +
            "  options { chunkDirectory = \"" + tempChunkDir.toString().replace("\\", "/") + "\" }\n" +
            "}\n"
        );
        database = new H2Database("test-db", dbConfig);

        // Start embedded Javalin server
        app = Javalin.create().start(0);
        port = app.port();

        // Register EnvironmentController
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(IDatabaseReaderProvider.class, database);
        EnvironmentController controller = new EnvironmentController(registry, ConfigFactory.empty());
        controller.registerRoutes(app, "/visualizer/api/environment");
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
        if (database != null) {
            database.close();
        }
    }

    @Test
    void getEnvironment_returns404OnInvalidRunId() {
        // When: Make request with non-existent runId
        given()
            .port(port)
            .basePath("/visualizer/api/environment")
            .queryParam("region", "0,10,0,10")
            .queryParam("runId", "invalid_run")
            .get("/100")
        .then()
            .statusCode(404);
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, messagePattern = ".*Region must have even number.*")
    void getEnvironment_returns400OnInvalidRegion() {
        // When: Make request with invalid region (odd number of values)
        given()
            .port(port)
            .basePath("/visualizer/api/environment")
            .queryParam("region", "0,50,0")  // Only 3 values (invalid)
            .queryParam("runId", "test_run")
            .get("/100")
        .then()
            .statusCode(400);
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, messagePattern = ".*Invalid tick number.*")
    void getEnvironment_returns400OnInvalidTick() {
        // When: Make request with non-numeric tick
        given()
            .port(port)
            .basePath("/visualizer/api/environment")
            .queryParam("region", "0,10,0,10")
            .get("/invalid")
        .then()
            .statusCode(400);
    }

    @Test
    void getEnvironment_returns404OnNoRuns() {
        // Given: Empty database (no runs)
        // When: Make request without runId parameter
        given()
            .port(port)
            .basePath("/visualizer/api/environment")
            .queryParam("region", "0,10,0,10")
            // Note: NO runId parameter - should try to find latest run
            .get("/100")
        .then()
            .statusCode(404);
    }

    @Test
    void getEnvironment_returns404OnNonExistentRun() {
        // Given: Database without proper schema for this runId
        // When: Make request for non-existent runId
        // Then: Should return 404 (run ID not found)
        given()
            .port(port)
            .basePath("/visualizer/api/environment")
            .queryParam("region", "0,10,0,10")
            .queryParam("runId", "test_run")
            .get("/100")
        .then()
            .statusCode(404);
    }

    
}
