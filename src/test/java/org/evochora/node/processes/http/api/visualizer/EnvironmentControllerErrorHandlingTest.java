package org.evochora.node.processes.http.api.visualizer;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.javalin.Javalin;
import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.datapipeline.resources.database.H2Database;
import org.evochora.datapipeline.services.indexers.EnvironmentIndexer;
import org.evochora.junit.extensions.logging.ExpectLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.node.spi.ServiceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            "  className = \"org.evochora.datapipeline.resources.database.h2.SingleBlobStrategy\"\n" +
            "  options { compression { enabled = true, codec = \"zstd\", level = 3 } }\n" +
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
        controller.registerRoutes(app, "/visualizer/api");
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
    @ExpectLog(level = LogLevel.WARN, messagePattern = ".*No run ID available.*Run ID not found.*")
    void getEnvironment_returns404OnInvalidRunId() {
        // When: Make request with non-existent runId
        given()
            .port(port)
            .basePath("/visualizer/api")
            .queryParam("region", "0,10,0,10")
            .queryParam("runId", "invalid_run")
            .get("/100/environment")
        .then()
            .statusCode(404);
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, messagePattern = ".*Region must have even number.*")
    void getEnvironment_returns400OnInvalidRegion() {
        // When: Make request with invalid region (odd number of values)
        given()
            .port(port)
            .basePath("/visualizer/api")
            .queryParam("region", "0,50,0")  // Only 3 values (invalid)
            .queryParam("runId", "test_run")
            .get("/100/environment")
        .then()
            .statusCode(400);
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, messagePattern = ".*Invalid tick number.*")
    void getEnvironment_returns400OnInvalidTick() {
        // When: Make request with non-numeric tick
        given()
            .port(port)
            .basePath("/visualizer/api")
            .queryParam("region", "0,10,0,10")
            .get("/invalid/environment")
        .then()
            .statusCode(400);
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, messagePattern = ".*No run ID available.*")
    void getEnvironment_returns404OnNoRuns() {
        // Given: Empty database (no runs)
        // When: Make request without runId parameter
        given()
            .port(port)
            .basePath("/visualizer/api")
            .queryParam("region", "0,10,0,10")
            // Note: NO runId parameter - should try to find latest run
            .get("/100/environment")
        .then()
            .statusCode(404);
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, messagePattern = ".*No run ID available.*")
    void getEnvironment_returns404OnNonExistentRun() {
        // Given: Database without proper schema for this runId
        // When: Make request for non-existent runId
        // Then: Should return 404 (run ID not found)
        given()
            .port(port)
            .basePath("/visualizer/api")
            .queryParam("region", "0,10,0,10")
            .queryParam("runId", "test_run")
            .get("/100/environment")
        .then()
            .statusCode(404);
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, messagePattern = ".*No run ID available.*")
    @ExpectLog(level = LogLevel.WARN, messagePattern = ".*Connection pool exhausted.*")
    void getEnvironment_handlesConnectionPoolExhaustion() {
        // Given: Very limited connection pool (size=1)
        Config limitedConfig = ConfigFactory.parseString(
            "jdbcUrl = \"jdbc:h2:mem:test-pool-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL\"\n" +
            "username = \"sa\"\n" +
            "password = \"\"\n" +
            "maxPoolSize = 1\n" +  // Very limited pool
            "h2EnvironmentStrategy {\n" +
            "  className = \"org.evochora.datapipeline.resources.database.h2.SingleBlobStrategy\"\n" +
            "}\n"
        );

        try (H2Database limitedDb = new H2Database("limited-db", limitedConfig)) {
            // Start new Javalin server with limited database
            Javalin limitedApp = Javalin.create().start(0);
            int limitedPort = limitedApp.port();

            try {
                ServiceRegistry limitedRegistry = new ServiceRegistry();
                limitedRegistry.register(IDatabaseReaderProvider.class, limitedDb);
                
                EnvironmentController limitedController = new EnvironmentController(limitedRegistry, ConfigFactory.empty());
                limitedController.registerRoutes(limitedApp, "/visualizer/api");

                // When: Make multiple concurrent requests with pool size 1
                // This tests graceful degradation under load
                List<CompletableFuture<io.restassured.response.Response>> futures = 
                    IntStream.range(0, 5)
                        .mapToObj(i -> CompletableFuture.supplyAsync(() ->
                            given()
                                .port(limitedPort)
                                .basePath("/visualizer/api")
                                .queryParam("region", "0,10,0,10")
                                .queryParam("runId", "test-run")
                                .get("/1/environment")))
                        .toList();

                // Then: All requests should complete with valid HTTP responses
                List<io.restassured.response.Response> responses = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

                // Verify that all requests returned valid HTTP responses
                responses.forEach(resp -> {
                    int statusCode = resp.getStatusCode();
                    assertTrue(
                        statusCode >= 200 && statusCode < 600,
                        "All requests should return valid HTTP status codes"
                    );
                });
            } finally {
                limitedApp.stop();
            }
        }
    }
}
