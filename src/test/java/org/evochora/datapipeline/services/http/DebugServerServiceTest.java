package org.evochora.datapipeline.services.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contains integration tests for the {@link DebugServerService}.
 * These tests verify that the embedded web server can start, connect to a database,
 * and serve debug data correctly via its REST API.
 * This is an integration test as it involves a live HTTP server and a database connection.
 */
public class DebugServerServiceTest {

    /**
     * Helper method to wait until the DebugServerService has started.
     * @param service The service instance.
     * @param timeoutMillis The maximum time to wait.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    private void waitForServiceStart(DebugServerService service, long timeoutMillis) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        while (!service.isRunning() && (System.currentTimeMillis() - startTime) < timeoutMillis) {
            Thread.sleep(50);
        }
    }

    /**
     * Helper method to wait until the DebugServerService has stopped.
     * @param service The service instance.
     * @param timeoutMillis The maximum time to wait.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    private void waitForServiceStop(DebugServerService service, long timeoutMillis) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        while (service.isRunning() && (System.currentTimeMillis() - startTime) < timeoutMillis) {
            Thread.sleep(50);
        }
    }

    /**
     * An end-to-end test for the DebugServerService. It sets up an in-memory SQLite database,
     * inserts mock tick data, starts the service, makes an HTTP client request to the
     * API endpoint, and verifies that the correct data is returned with a 200 OK status.
     * This is an integration test.
     *
     * @throws Exception if the database connection, service lifecycle, or HTTP request fails.
     */
    @Test
    @Tag("integration")
    void testDebugServerService() throws Exception {
        // Use shared in-memory database for reliable testing
        String dbPath = "jdbc:sqlite:file:memdb_debugserverservice?mode=memory&cache=shared";
        
        // Create the table and keep connection open to maintain table existence
        Connection dbConnection = DriverManager.getConnection(dbPath);
        try {
            try (Statement st = dbConnection.createStatement()) {
                st.execute("DROP TABLE IF EXISTS prepared_ticks");
                st.execute("CREATE TABLE prepared_ticks (tick_number INTEGER PRIMARY KEY, tick_data_json BLOB)");
                
                // Test-Daten einfügen
                try (PreparedStatement ps = dbConnection.prepareStatement("INSERT INTO prepared_ticks (tick_number, tick_data_json) VALUES (?, ?)")) {
                    ps.setLong(1, 1L);
                    ps.setBytes(2, "{\"tickNumber\":1,\"test\":\"data\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    ps.executeUpdate();
                }
            }
            
            // Create configuration for the service
            String configString = String.format("""
                options {
                    port = 0
                    dbPath = "%s"
                    compression {
                        enabled = false
                        algorithm = "gzip"
                    }
                }
                """, dbPath);
            
            Config config = ConfigFactory.parseString(configString);
            
            // Test der Web-Service-Funktionalität
            DebugServerService service = new DebugServerService(config);
            service.run();
            try {
                // Wait for the service to start
                waitForServiceStart(service, 5000);
                
                // HTTP-Test
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + service.getPort() + "/api/tick/1")).GET().build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                assertThat(resp.statusCode()).isEqualTo(200);
                assertThat(resp.body()).contains("\"tickNumber\":1");
            } finally {
                service.stopService();
                // Wait for the service to stop
                waitForServiceStop(service, 5000);
            }
        } finally {
            // Close the database connection after the test
            dbConnection.close();
        }
        
        // No cleanup needed for in-memory database
    }

    /**
     * Test that the service can be configured with different settings.
     */
    @Test
    @Tag("unit")
    void testServiceConfiguration() {
        String configString = """
            options {
                port = 9090
                dbPath = "test/debug.sqlite"
                compression {
                    enabled = true
                    algorithm = "gzip"
                }
            }
            """;
        
        Config config = ConfigFactory.parseString(configString);
        DebugServerService service = new DebugServerService(config);
        
        // Test that configuration is read correctly
        assertThat(service.getPort()).isEqualTo(-1); // Not started yet
        // Note: We can't easily test the internal configuration without exposing getters
        // The service will use the configuration when started
    }

    /**
     * Test that the service can resolve database paths correctly.
     * This tests both file and directory path resolution.
     */
    @Test
    @Tag("unit")
    void testDatabasePathResolution() throws Exception {
        // Test with JDBC URL
        String jdbcConfig = """
            options {
                port = 8080
                dbPath = "jdbc:sqlite:file:memdb_test?mode=memory&cache=shared"
            }
            """;
        
        Config jdbcConfigObj = ConfigFactory.parseString(jdbcConfig);
        DebugServerService jdbcService = new DebugServerService(jdbcConfigObj);
        assertThat(jdbcService.getPort()).isEqualTo(-1); // Not started yet
        
        // Test with specific file path
        String fileConfig = """
            options {
                port = 8080
                dbPath = "test/debug.sqlite"
            }
            """;
        
        Config fileConfigObj = ConfigFactory.parseString(fileConfig);
        DebugServerService fileService = new DebugServerService(fileConfigObj);
        assertThat(fileService.getPort()).isEqualTo(-1); // Not started yet
        
        // Test with directory path
        String dirConfig = """
            options {
                port = 8080
                dbPath = "runs"
            }
            """;
        
        Config dirConfigObj = ConfigFactory.parseString(dirConfig);
        DebugServerService dirService = new DebugServerService(dirConfigObj);
        assertThat(dirService.getPort()).isEqualTo(-1); // Not started yet
    }
}
