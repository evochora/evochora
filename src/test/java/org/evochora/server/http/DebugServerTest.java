package org.evochora.server.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

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
 * Contains integration tests for the {@link DebugServer}.
 * These tests verify that the embedded web server can start, connect to a database,
 * and serve debug data correctly via its REST API.
 * This is an integration test as it involves a live HTTP server and a database connection.
 */
public class DebugServerTest {

    /**
     * Helper method to wait until the DebugServer has started.
     * @param server The server instance.
     * @param timeoutMillis The maximum time to wait.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    private void waitForServerStart(DebugServer server, long timeoutMillis) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        while (!server.isRunning() && (System.currentTimeMillis() - startTime) < timeoutMillis) {
            Thread.sleep(50);
        }
    }

    /**
     * Helper method to wait until the DebugServer has stopped.
     * @param server The server instance.
     * @param timeoutMillis The maximum time to wait.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    private void waitForServerStop(DebugServer server, long timeoutMillis) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        while (server.isRunning() && (System.currentTimeMillis() - startTime) < timeoutMillis) {
            Thread.sleep(50);
        }
    }

    /**
     * An end-to-end test for the DebugServer. It sets up an in-memory SQLite database,
     * inserts mock tick data, starts the server, makes an HTTP client request to the
     * API endpoint, and verifies that the correct data is returned with a 200 OK status.
     * This is an integration test.
     *
     * @throws Exception if the database connection, server lifecycle, or HTTP request fails.
     */
    @Test
    @Tag("integration")
    void testDebugServer() throws Exception {
        // Use in-memory database for reliable testing without file locking issues
        String dbPath = "jdbc:sqlite:file:memdb_debugserver?mode=memory&cache=shared";
        
        // Create the table and keep connection open to maintain table existence
        Connection dbConnection = DriverManager.getConnection(dbPath);
        try {
            try (Statement st = dbConnection.createStatement()) {
                st.execute("DROP TABLE IF EXISTS prepared_ticks");
                st.execute("CREATE TABLE prepared_ticks (tick_number INTEGER PRIMARY KEY, tick_data_json TEXT)");
                
                // Test-Daten einfügen
                try (PreparedStatement ps = dbConnection.prepareStatement("INSERT INTO prepared_ticks (tick_number, tick_data_json) VALUES (?, ?)")) {
                    ps.setLong(1, 1L);
                    ps.setString(2, "{\"tickNumber\":1,\"test\":\"data\"}");
                    ps.executeUpdate();
                }
            }
            
            // Test der Web-Service-Funktionalität
            DebugServer web = new DebugServer();
            web.start(dbPath, 0);
            try {
                // Wait for the server to start
                waitForServerStart(web, 5000);
                
                // HTTP-Test
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + web.getPort() + "/api/tick/1")).GET().build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                assertThat(resp.statusCode()).isEqualTo(200);
                assertThat(resp.body()).contains("\"tickNumber\":1");
            } finally {
                web.stop();
                // Wait for the server to stop
                waitForServerStop(web, 5000);
            }
        } finally {
            // Close the database connection after the test
            dbConnection.close();
        }
        
        // No cleanup needed for in-memory database
    }
}
