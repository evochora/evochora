package org.evochora.server.http;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

public class AnalysisWebServiceTest {

    @Test
    void servesPreparedTickJson() throws Exception {
        Path tmp = Files.createTempFile("prepared_ticks_test", ".sqlite");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + tmp.toAbsolutePath());
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS prepared_ticks (tick_number INTEGER PRIMARY KEY, tick_data_json TEXT)");
            st.execute("INSERT OR REPLACE INTO prepared_ticks(tick_number, tick_data_json) VALUES (1, '{" +
                    "\"schemaVersion\":1,\"mode\":\"performance\",\"generatedAtUtc\":\"x\",\"tickNumber\":1,\"worldMeta\":{\"shape\":[10,10]},\"worldState\":{\"cells\":[],\"organisms\":[]},\"organismDetails\":{}}')");
        }

        AnalysisWebService web = new AnalysisWebService();
        web.start(tmp.toString(), 7089);
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:7089/api/tick/1")).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.body()).contains("\"tickNumber\":1");
        } finally {
            web.stop();
            Files.deleteIfExists(tmp);
        }
    }
}


