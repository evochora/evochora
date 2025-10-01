package org.evochora.node;

import com.typesafe.config.Config;
import io.restassured.RestAssured;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.cli.config.ConfigLoader;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

@Tag("integration")
@DisplayName("Node End-to-End API Integration Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(LogWatchExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AllowLog(level = LogLevel.WARN, messagePattern = "Could not perform action on service.*")
class NodeIntegrationTest {

    private static final String TEST_CONFIG_FILE = "org/evochora/node/evochora-test.conf";
    private static final int TEST_PORT = 8081;
    private static final String BASE_PATH = "/pipeline/api";

    private Node testNode;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @BeforeAll
    void startNode() {
        RestAssured.baseURI = "http://127.0.0.1";
        RestAssured.port = TEST_PORT;

        final Config testConfig = ConfigLoader.load(TEST_CONFIG_FILE);
        testNode = new Node(testConfig);

        executor.submit(testNode::start);

        await().atMost(10, TimeUnit.SECONDS).until(() -> {
            try {
                given().when().get(BASE_PATH + "/status").then().statusCode(200);
                return true;
            } catch (final Exception e) {
                return false;
            }
        });
    }

    @AfterAll
    void stopNode() {
        if (testNode != null) {
            testNode.stop();
        }
        executor.shutdownNow();
    }

    @BeforeEach
    void resetServicesToStopped() {
        // This ensures each test starts from a clean, predictable state.
        given().post(BASE_PATH + "/stop").then().statusCode(202);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
            given().get(BASE_PATH + "/status").then().body("status", equalTo("STOPPED"))
        );
    }

    @Test
    @Order(1)
    @DisplayName("GET /status - should initially return STOPPED status with two services")
    void getStatus_initially_returnsStopped() {
        given()
            .when()
                .get(BASE_PATH + "/status")
            .then()
                .statusCode(200)
                .body("status", equalTo("STOPPED"))
                .body("services", hasSize(2));
    }

    @Test
    @Order(2)
    @DisplayName("POST /start and /stop - should correctly change the pipeline state")
    void postStartAndStop_changesState() {
        // Start all services
        given().post(BASE_PATH + "/start").then().statusCode(202);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
            given().get(BASE_PATH + "/status").then().body("status", equalTo("RUNNING"))
        );

        // Stop all services
        given().post(BASE_PATH + "/stop").then().statusCode(202);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
            given().get(BASE_PATH + "/status").then().body("status", equalTo("STOPPED"))
        );
    }

    @Test
    @Order(3)
    @DisplayName("POST /service/{name}/start - should start one service and result in DEGRADED status")
    void postStartSingleService_resultsInDegradedStatus() {
        // Start only the consumer
        given().post(BASE_PATH + "/service/test-consumer/start").then().statusCode(202);

        // Verify the status
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
            given()
                .when().get(BASE_PATH + "/status")
                .then()
                    .statusCode(200)
                    .body("status", equalTo("DEGRADED"))
                    .body("services.find { it.name == 'test-consumer' }.state", equalTo("RUNNING"))
                    .body("services.find { it.name == 'test-producer' }.state", equalTo("STOPPED"))
        );
    }

    @Test
    @Order(4)
    @AllowLog(level = LogLevel.WARN, messagePattern = "Not found handler triggered.*")
    @DisplayName("GET /service/{name}/status - should return 404 for a non-existent service")
    void getStatusForNonExistentService_returns404() {
        given()
            .when()
                .get(BASE_PATH + "/service/non-existent-service/status")
            .then()
                .statusCode(404)
                .body("error", equalTo("Not Found"))
                .body("message", equalTo("Service not found: non-existent-service"));
    }

    @Test
    @Order(5)
    @AllowLog(level = LogLevel.WARN, messagePattern = "Invalid state transition for request.*")
    @DisplayName("POST /service/{name}/start - should return 409 when starting an already running service")
    void postStartOnRunningService_returns409() {
        // Start the service
        given().post(BASE_PATH + "/service/test-consumer/start").then().statusCode(202);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
            given().get(BASE_PATH + "/service/test-consumer/status").then().body("state", equalTo("RUNNING"))
        );

        // Try to start it again
        given()
            .when()
                .post(BASE_PATH + "/service/test-consumer/start")
            .then()
                .statusCode(409) // Conflict
                .body("error", equalTo("Conflict"))
                .body("message", containsString("Service 'test-consumer' is already running. Use restartService() for an explicit restart."));
    }
}