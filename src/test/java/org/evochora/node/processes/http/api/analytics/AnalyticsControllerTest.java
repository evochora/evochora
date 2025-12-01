package org.evochora.node.processes.http.api.analytics;

import com.typesafe.config.ConfigFactory;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.evochora.datapipeline.api.resources.storage.IAnalyticsStorageRead;
import org.evochora.node.spi.ServiceRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
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

        Javalin app = Javalin.create();
        controller.registerRoutes(app, "/api");

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

        Javalin app = Javalin.create();
        controller.registerRoutes(app, "/api");

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
    void testFileServing() throws Exception {
        IAnalyticsStorageRead storage = mock(IAnalyticsStorageRead.class);
        byte[] content = "parquet-data".getBytes();
        
        when(storage.openAnalyticsInputStream(eq("run1"), eq("data.parquet")))
            .thenReturn(new ByteArrayInputStream(content));

        ServiceRegistry registry = new ServiceRegistry();
        registry.register(IAnalyticsStorageRead.class, storage);
        AnalyticsController controller = new AnalyticsController(registry, ConfigFactory.empty());

        Javalin app = Javalin.create();
        controller.registerRoutes(app, "/api");

        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/api/files/data.parquet?runId=run1");
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().bytes()).isEqualTo(content);
            assertThat(response.header("Content-Type")).isEqualTo("application/octet-stream");
        });
    }
}

