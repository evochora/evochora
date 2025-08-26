package org.evochora.server.http;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

public class DebugServerTest {

    @Test
    void testPreparedTicksEndpoint() throws Exception {
        // Verwende eine echte SQLite-Datei für Tests (einfacher als In-Memory)
        String dbPath = "test_debugserver.sqlite";
        
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            try (Statement st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS prepared_ticks");
                st.execute("CREATE TABLE prepared_ticks (tick_number INTEGER PRIMARY KEY, tick_data_json TEXT)");
                
                // Test-Daten einfügen
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO prepared_ticks (tick_number, tick_data_json) VALUES (?, ?)")) {
                    ps.setLong(1, 1L);
                    ps.setString(2, "{\"tickNumber\":1,\"test\":\"data\"}");
                    ps.executeUpdate();
                }
            }
        }
        
        // Test der Web-Service-Funktionalität
        DebugServer web = new DebugServer();
        web.start(dbPath, 0);
        try {
            // Warte länger, bis der Server gestartet ist
            Thread.sleep(1000);
            
            // HTTP-Test
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + web.getPort() + "/api/tick/1")).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.body()).contains("\"tickNumber\":1");
        } finally {
            web.stop();
            // Warte kurz, bis der Server gestoppt ist
            Thread.sleep(200);
        }
        
        // Aufräumen: Test-Datei löschen
        try {
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(dbPath));
        } catch (Exception e) {
            // Ignoriere Fehler beim Löschen
        }
    }
}


