package org.evochora.node.processes.http.api.analytics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.typesafe.config.ConfigFactory;
import io.javalin.Javalin;
import org.evochora.node.processes.http.JavalinFactory;
import io.javalin.testtools.JavalinTest;
import org.evochora.datapipeline.api.resources.storage.IAnalyticsStorageRead;
import org.evochora.junit.extensions.logging.ExpectLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.node.spi.ServiceRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Tag("unit")
class AnalyticsControllerTest {

    @Test
    void testManifestAggregation() throws Exception {
        // 1. Mock Storage
        IAnalyticsStorageRead storage = mock(IAnalyticsStorageRead.class);
        
        // Simulate 2 metrics present
        when(storage.listAnalyticsFiles(eq("run1"), eq("")))
            .thenReturn(List.of(
                "pop/metadata.json",
                "pop/raw/data.parquet",
                "mol/metadata.json"
            ));

        // Mock metadata content
        when(storage.openAnalyticsInputStream(eq("run1"), eq("pop/metadata.json")))
            .thenReturn(new ByteArrayInputStream("{\"id\":\"pop\",\"name\":\"Population\"}".getBytes()));
        
        when(storage.openAnalyticsInputStream(eq("run1"), eq("mol/metadata.json")))
            .thenReturn(new ByteArrayInputStream("{\"id\":\"mol\",\"name\":\"Molecules\"}".getBytes()));

        // 2. Setup Controller
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(IAnalyticsStorageRead.class, storage);
        
        AnalyticsController controller = new AnalyticsController(
            registry, 
            ConfigFactory.parseMap(Map.of("analyticsManifestCacheTtlSeconds", 1))
        );

        Javalin app = JavalinFactory.create(config -> controller.registerRoutes(config.routes, "/api"));

        // 3. Test Request
        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/api/manifest?runId=run1");
            assertThat(response.code()).isEqualTo(200);
            
            String json = response.body().string();
            assertThat(json).contains("\"id\":\"pop\"");
            assertThat(json).contains("\"name\":\"Population\"");
            assertThat(json).contains("\"id\":\"mol\"");
            
            // Verify aggregation structure
            assertThat(json).contains("\"metrics\":[");
        });
    }

    @Test
    void theManifestCarriesWhereEachCardStandsFromThePluginList() throws Exception {
        IAnalyticsStorageRead storage = mock(IAnalyticsStorageRead.class);
        when(storage.listAnalyticsFiles(eq("run1"), eq("")))
            .thenReturn(List.of("age_distribution/metadata.json", "population/metadata.json"));
        when(storage.openAnalyticsInputStream(eq("run1"), eq("age_distribution/metadata.json")))
            .thenReturn(new ByteArrayInputStream("{\"id\":\"age_distribution\"}".getBytes()));
        when(storage.openAnalyticsInputStream(eq("run1"), eq("population/metadata.json")))
            .thenReturn(new ByteArrayInputStream("{\"id\":\"population\"}".getBytes()));
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(IAnalyticsStorageRead.class, storage);

        // The plugin list names population first and gives the age distribution a row of its own
        AnalyticsController controller = new AnalyticsController(registry, ConfigFactory.parseString("""
            plugins = [
              { className = "P", options { metricId = "population", group = "Population" } },
              { className = "A", options { metricId = "age_distribution", group = "Population", fullWidth = true } }
            ]
            """));
        Javalin app = JavalinFactory.create(config -> controller.registerRoutes(config.routes, "/api"));

        JavalinTest.test(app, (server, client) -> {
            JsonArray metrics = JsonParser.parseString(client.get("/api/manifest?runId=run1").body().string())
                .getAsJsonObject().getAsJsonArray("metrics");
            JsonObject first = metrics.get(0).getAsJsonObject();
            JsonObject second = metrics.get(1).getAsJsonObject();

            assertThat(first.get("id").getAsString()).isEqualTo("population");
            assertThat(first.get("group").getAsString()).isEqualTo("Population");
            assertThat(first.has("fullWidth")).as("a card of no width says nothing about it").isFalse();
            assertThat(first.get("order").getAsInt()).isZero();
            assertThat(second.get("id").getAsString()).isEqualTo("age_distribution");
            assertThat(second.get("fullWidth").getAsBoolean()).isTrue();
            assertThat(second.get("order").getAsInt()).isEqualTo(1);
        });
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, messagePattern = "Run run1: the card 'mutation_success' reads the table 'births', which this run holds nothing under and no plugin of this node writes.*")
    void aCardWhoseCompanionTableTheRunDoesNotHoldIsReported() throws Exception {
        IAnalyticsStorageRead storage = mock(IAnalyticsStorageRead.class);
        when(storage.listAnalyticsFiles(eq("run1"), eq("")))
            .thenReturn(List.of("mutation_success/metadata.json", "genome_diversity/metadata.json",
                "generation_time/metadata.json", "generation_time/lod0/0/t_0_9.parquet",
                "genome/lod0/0/g_0_9.parquet"));
        // Reads a table no entry of the run is stored under
        when(storage.openAnalyticsInputStream(eq("run1"), eq("mutation_success/metadata.json")))
            .thenReturn(new ByteArrayInputStream(("{\"id\":\"mutation_success\","
                + "\"storageMetricId\":\"variation_sources\","
                + "\"companions\":[{\"metricId\":\"births\",\"query\":\"q\"}]}").getBytes()));
        // Reads the table another card is stored under, and the one it is stored under itself
        when(storage.openAnalyticsInputStream(eq("run1"), eq("genome_diversity/metadata.json")))
            .thenReturn(new ByteArrayInputStream(("{\"id\":\"genome_diversity\","
                + "\"storageMetricId\":\"genome\","
                + "\"companions\":[{\"metricId\":\"generation_time\",\"query\":\"q\"},"
                + "{\"metricId\":\"genome\",\"query\":\"q\"}]}").getBytes()));
        when(storage.openAnalyticsInputStream(eq("run1"), eq("generation_time/metadata.json")))
            .thenReturn(new ByteArrayInputStream("{\"id\":\"generation_time\"}".getBytes()));
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(IAnalyticsStorageRead.class, storage);

        // A node that writes the variation sources but no births: nothing will ever fill that table
        AnalyticsController controller = new AnalyticsController(registry, ConfigFactory.parseString("""
            analyticsManifestCacheTtlSeconds = 1
            plugins = [
              { className = "V", options { metricId = "variation_sources", group = "Evolution" } }
            ]
            """));
        Javalin app = JavalinFactory.create(config -> controller.registerRoutes(config.routes, "/api"));

        JavalinTest.test(app, (server, client) -> {
            assertThat(client.get("/api/manifest?runId=run1").code()).isEqualTo(200);
        });
    }

    @Test
    void aCompanionTableWithFilesIsHeldEvenWhereItWritesNoManifestEntry() throws Exception {
        IAnalyticsStorageRead storage = mock(IAnalyticsStorageRead.class);
        // A plugin that writes no card writes no metadata.json, only its files
        when(storage.listAnalyticsFiles(eq("run1"), eq("")))
            .thenReturn(List.of("mutation_success/metadata.json", "births/lod0/0/births_0_9.parquet"));
        when(storage.openAnalyticsInputStream(eq("run1"), eq("mutation_success/metadata.json")))
            .thenReturn(new ByteArrayInputStream(("{\"id\":\"mutation_success\","
                + "\"storageMetricId\":\"variation_sources\","
                + "\"companions\":[{\"metricId\":\"births\",\"query\":\"q\"}]}").getBytes()));
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(IAnalyticsStorageRead.class, storage);

        // Not configured here either, so only its files can say the run holds it
        AnalyticsController controller = new AnalyticsController(registry, ConfigFactory.parseString("""
            analyticsManifestCacheTtlSeconds = 1
            plugins = [
              { className = "V", options { metricId = "variation_sources", group = "Evolution" } }
            ]
            """));
        Javalin app = JavalinFactory.create(config -> controller.registerRoutes(config.routes, "/api"));

        JavalinTest.test(app, (server, client) -> {
            assertThat(client.get("/api/manifest?runId=run1").code()).isEqualTo(200);
        });
    }

    @Test
    void testManifestCaching() throws Exception {
        IAnalyticsStorageRead storage = mock(IAnalyticsStorageRead.class);
        when(storage.listAnalyticsFiles(anyString(), anyString())).thenReturn(List.of("pop/metadata.json"));
        when(storage.openAnalyticsInputStream(anyString(), anyString()))
            .thenReturn(new ByteArrayInputStream("{}".getBytes()));

        ServiceRegistry registry = new ServiceRegistry();
        registry.register(IAnalyticsStorageRead.class, storage);
        
        // TTL 60 seconds
        AnalyticsController controller = new AnalyticsController(
            registry, 
            ConfigFactory.parseMap(Map.of("analyticsManifestCacheTtlSeconds", 60))
        );

        Javalin app = JavalinFactory.create(config -> controller.registerRoutes(config.routes, "/api"));

        JavalinTest.test(app, (server, client) -> {
            // First call
            client.get("/api/manifest?runId=run1");
            verify(storage, times(1)).listAnalyticsFiles(anyString(), anyString());
            
            // Second call (should be cached)
            client.get("/api/manifest?runId=run1");
            verify(storage, times(1)).listAnalyticsFiles(anyString(), anyString()); // Count remains 1
        });
    }

    @Test
    void emptyManifestIsNotCached() throws Exception {
        IAnalyticsStorageRead storage = mock(IAnalyticsStorageRead.class);
        when(storage.listAnalyticsFiles(anyString(), anyString()))
            .thenReturn(List.of())
            .thenReturn(List.of("pop/metadata.json"));
        when(storage.openAnalyticsInputStream(anyString(), anyString()))
            .thenReturn(new ByteArrayInputStream("{\"id\":\"pop\"}".getBytes()));

        ServiceRegistry registry = new ServiceRegistry();
        registry.register(IAnalyticsStorageRead.class, storage);

        AnalyticsController controller = new AnalyticsController(
            registry,
            ConfigFactory.parseMap(Map.of("analyticsManifestCacheTtlSeconds", 60))
        );

        Javalin app = JavalinFactory.create(config -> controller.registerRoutes(config.routes, "/api"));

        JavalinTest.test(app, (server, client) -> {
            assertThat(client.get("/api/manifest?runId=run1").body().string()).contains("\"metrics\":[]");
            assertThat(client.get("/api/manifest?runId=run1").body().string()).contains("\"id\":\"pop\"");
        });
    }

    private static final String TABLE_JSON = """
        {"id":"pop","description":"The living organisms; it's one row per recorded tick.",
         "tickIntervals":{"lod0":10},
         "columns":[{"name":"tick","type":"BIGINT","summed":false,"description":"The recorded tick the row describes."},
                    {"name":"alive","type":"INTEGER","summed":false,"description":"Organisms alive at the tick."}]}
        """;

    @Test
    void theManifestListsTheTablesOfARunWithWhatTheyHold() throws Exception {
        IAnalyticsStorageRead storage = mock(IAnalyticsStorageRead.class);
        when(storage.listAnalyticsFiles(eq("run1"), eq("")))
            .thenReturn(List.of("pop/table.json", "pop/metadata.json", "pop/lod0/000/a.parquet", "lineage/table.json"));
        when(storage.openAnalyticsInputStream(eq("run1"), eq("pop/metadata.json")))
            .thenAnswer(call -> new ByteArrayInputStream("{\"id\":\"pop\",\"name\":\"Population\"}".getBytes()));
        when(storage.openAnalyticsInputStream(eq("run1"), eq("pop/table.json")))
            .thenAnswer(call -> new ByteArrayInputStream(TABLE_JSON.getBytes()));
        when(storage.openAnalyticsInputStream(eq("run1"), eq("lineage/table.json")))
            .thenAnswer(call -> new ByteArrayInputStream(
                "{\"id\":\"lineage\",\"description\":\"Which genome arose from which.\",\"columns\":[]}".getBytes()));

        ServiceRegistry registry = new ServiceRegistry();
        registry.register(IAnalyticsStorageRead.class, storage);
        AnalyticsController controller = new AnalyticsController(registry, ConfigFactory.empty());
        Javalin app = JavalinFactory.create(config -> controller.registerRoutes(config.routes, "/api"));

        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/api/manifest?runId=run1");
            assertThat(response.code()).isEqualTo(200);

            JsonObject manifest = JsonParser.parseString(response.body().string()).getAsJsonObject();
            assertThat(manifest.getAsJsonArray("metrics")).hasSize(1);
            JsonArray tables = manifest.getAsJsonArray("tables");
            // A table needs no chart to be listed, and a chart is no table
            assertThat(tables).hasSize(2);
            assertThat(tables.get(0).getAsJsonObject().get("id").getAsString()).isEqualTo("lineage");
            JsonObject pop = tables.get(1).getAsJsonObject();
            assertThat(pop.get("description").getAsString()).startsWith("The living organisms");
            assertThat(pop.getAsJsonObject("tickIntervals").get("lod0").getAsInt()).isEqualTo(10);
            assertThat(pop.getAsJsonArray("columns").get(1).getAsJsonObject().get("description").getAsString())
                .isEqualTo("Organisms alive at the tick.");
        });
    }

    @Test
    void aParquetFileThatLeavesTheNodeCarriesTheDescriptionOfItsTable(@TempDir Path dir) throws Exception {
        assertThat(servedKeyValueMetadata(dir, TABLE_JSON)).containsExactly(
            Map.entry("description", "The living organisms; it's one row per recorded tick."),
            Map.entry("description.tick", "The recorded tick the row describes."),
            Map.entry("description.alive", "Organisms alive at the tick."));
    }

    /**
     * Serves a table of two rows through the parquet endpoint and reads the key-value metadata of
     * the file that arrives.
     *
     * @param dir       a directory for the files of the test
     * @param tableJson the description the run holds for the table
     * @return the keys and values of the served file, in file order
     */
    private Map<String, String> servedKeyValueMetadata(Path dir, String tableJson) throws Exception {
        Path source = dir.resolve("source.parquet");
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE t (tick BIGINT, alive INTEGER)");
            stmt.execute("INSERT INTO t VALUES (0, 5), (10, 7)");
            stmt.execute("COPY t TO '" + source.toString().replace("\\", "/") + "' (FORMAT PARQUET)");
        }
        byte[] parquet = Files.readAllBytes(source);

        IAnalyticsStorageRead storage = mock(IAnalyticsStorageRead.class);
        when(storage.listAnalyticsFiles(eq("run1"), eq("pop/lod0/"), any(), any()))
            .thenReturn(List.of("pop/lod0/000/a.parquet"));
        when(storage.openAnalyticsInputStream(eq("run1"), eq("pop/lod0/000/a.parquet")))
            .thenAnswer(call -> new ByteArrayInputStream(parquet));
        when(storage.openAnalyticsInputStream(eq("run1"), eq("pop/table.json")))
            .thenAnswer(call -> new ByteArrayInputStream(tableJson.getBytes()));

        ServiceRegistry registry = new ServiceRegistry();
        registry.register(IAnalyticsStorageRead.class, storage);
        AnalyticsController controller = new AnalyticsController(registry, ConfigFactory.empty());
        Javalin app = JavalinFactory.create(config -> controller.registerRoutes(config.routes, "/api"));

        Path served = dir.resolve("served.parquet");
        JavalinTest.test(app, (server, client) -> {
            // The test client reads a body as text only, which a Parquet file is not
            var response = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                    "http://localhost:" + server.port() + "/api/parquet?runId=run1&metric=pop&lod=lod0")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            Files.write(served, response.body());
        });

        Map<String, String> metadata = new LinkedHashMap<>();
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement stmt = conn.createStatement();
             ResultSet rows = stmt.executeQuery("SELECT decode(key), decode(value) FROM parquet_kv_metadata('"
                 + served.toString().replace("\\", "/") + "')")) {
            while (rows.next()) {
                metadata.put(rows.getString(1), rows.getString(2));
            }
        }
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement stmt = conn.createStatement();
             ResultSet rows = stmt.executeQuery("SELECT count(*) FROM read_parquet('"
                 + served.toString().replace("\\", "/") + "')")) {
            rows.next();
            assertThat(rows.getLong(1)).isEqualTo(2);
        }
        return metadata;
    }
}
