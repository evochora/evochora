package org.evochora.node.processes.http.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.resources.storage.IAnalyticsStorageRead;
import org.evochora.node.spi.ServiceRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.typesafe.config.ConfigFactory;

import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;

/**
 * Tests for the /tick-range endpoint on AnalyticsController.
 */
@Tag("unit")
class AnalyticsControllerTickRangeTest {

    private final Gson gson = new Gson();

    @Test
    void tickRange_returnsCorrectResponse() throws Exception {
        IAnalyticsStorageRead storage = mockStorage(
                new long[]{100L, 999900L},
                List.of("metric/lod1/000/batch_a.parquet", "metric/lod1/000/batch_b.parquet", "metric/lod1/metadata.json"));

        Javalin app = createApp(storage);
        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/api/tick-range?runId=run1&metric=metric&lod=lod1");
            assertThat(response.code()).isEqualTo(200);

            Map<?, ?> body = gson.fromJson(response.body().string(), Map.class);
            assertThat(((Number) body.get("tickMin")).longValue()).isEqualTo(100L);
            assertThat(((Number) body.get("tickMax")).longValue()).isEqualTo(999900L);
            assertThat(((Number) body.get("fileCount")).intValue()).isEqualTo(2); // excludes metadata.json
            assertThat(body.get("lod")).isEqualTo("lod1");
        });
    }

    @Test
    void tickRange_autoSelectsLod() throws Exception {
        IAnalyticsStorageRead storage = mock(IAnalyticsStorageRead.class);

        // resolveStorageMetric: no metadata.json → falls back to metric ID
        when(storage.openAnalyticsInputStream(eq("run1"), eq("metric/metadata.json")))
                .thenThrow(new IOException("not found"));

        // autoSelectLod: lists all files to find LODs
        when(storage.listAnalyticsFiles(eq("run1"), eq("metric/")))
                .thenReturn(List.of(
                        "metric/lod0/batch_a.parquet", "metric/lod0/batch_b.parquet",
                        "metric/lod0/batch_c.parquet", "metric/lod0/batch_d.parquet",
                        "metric/lod1/batch_a.parquet"));

        // After auto-select picks lod0 (4 files <= 50 threshold)
        when(storage.getAnalyticsTickRange(eq("run1"), eq("metric/lod0/")))
                .thenReturn(new long[]{0L, 500L});
        when(storage.listAnalyticsFiles(eq("run1"), eq("metric/lod0/")))
                .thenReturn(List.of(
                        "metric/lod0/batch_a.parquet", "metric/lod0/batch_b.parquet",
                        "metric/lod0/batch_c.parquet", "metric/lod0/batch_d.parquet"));

        Javalin app = createApp(storage);
        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/api/tick-range?runId=run1&metric=metric");
            assertThat(response.code()).isEqualTo(200);

            Map<?, ?> body = gson.fromJson(response.body().string(), Map.class);
            assertThat(body.get("lod")).isEqualTo("lod0");
            assertThat(((Number) body.get("fileCount")).intValue()).isEqualTo(4);
        });
    }

    @Test
    void tickRange_missingMetricReturns400() throws Exception {
        IAnalyticsStorageRead storage = mock(IAnalyticsStorageRead.class);

        Javalin app = createApp(storage);
        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/api/tick-range?runId=run1");
            assertThat(response.code()).isEqualTo(400);
        });
    }

    @Test
    void tickRange_noDataReturns404() throws Exception {
        IAnalyticsStorageRead storage = mock(IAnalyticsStorageRead.class);

        // resolveStorageMetric: no metadata
        when(storage.openAnalyticsInputStream(eq("run1"), anyString()))
                .thenThrow(new IOException("not found"));

        // autoSelectLod: no files at all
        when(storage.listAnalyticsFiles(eq("run1"), eq("empty/")))
                .thenReturn(List.of());

        Javalin app = createApp(storage);
        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/api/tick-range?runId=run1&metric=empty");
            assertThat(response.code()).isEqualTo(404);
        });
    }

    @Test
    void tickRange_noTickRangeReturnsNulls() throws Exception {
        IAnalyticsStorageRead storage = mockStorage(null, List.of("metric/lod0/metadata.json"));

        Javalin app = createApp(storage);
        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/api/tick-range?runId=run1&metric=metric&lod=lod0");
            assertThat(response.code()).isEqualTo(200);

            Map<?, ?> body = gson.fromJson(response.body().string(), Map.class);
            assertThat(body.get("tickMin")).isNull();
            assertThat(body.get("tickMax")).isNull();
            assertThat(((Number) body.get("fileCount")).intValue()).isEqualTo(0);
        });
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private IAnalyticsStorageRead mockStorage(long[] tickRange, List<String> files) throws Exception {
        IAnalyticsStorageRead storage = mock(IAnalyticsStorageRead.class);
        when(storage.openAnalyticsInputStream(eq("run1"), anyString()))
                .thenThrow(new IOException("not found"));
        when(storage.getAnalyticsTickRange(eq("run1"), anyString())).thenReturn(tickRange);
        when(storage.listAnalyticsFiles(eq("run1"), anyString())).thenReturn(files);
        return storage;
    }

    private Javalin createApp(IAnalyticsStorageRead storage) {
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(IAnalyticsStorageRead.class, storage);
        AnalyticsController controller = new AnalyticsController(
                registry, ConfigFactory.parseMap(Map.of("analyticsManifestCacheTtlSeconds", 1)));
        Javalin app = Javalin.create();
        controller.registerRoutes(app, "/api");
        return app;
    }
}
