package org.evochora.node.processes.http.api.analytics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.typesafe.config.Config;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.resources.storage.IAnalyticsStorageRead;
import org.evochora.node.spi.IController;
import org.evochora.node.spi.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves analytics artifacts and aggregates the manifest.
 */
public class AnalyticsController implements IController {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsController.class);
    private final IAnalyticsStorageRead storage;
    private final Gson gson = new GsonBuilder().create();
    
    // Caching for Manifest
    private final long cacheTtlMs;
    private final ConcurrentHashMap<String, CacheEntry> manifestCache = new ConcurrentHashMap<>();

    private record CacheEntry(long timestamp, String json) {}

    public AnalyticsController(ServiceRegistry registry, Config options) {
        this.storage = registry.get(IAnalyticsStorageRead.class);
        this.cacheTtlMs = options.hasPath("analyticsManifestCacheTtlSeconds") 
            ? options.getInt("analyticsManifestCacheTtlSeconds") * 1000L 
            : 30000L; // Default 30s
    }

    @Override
    public void registerRoutes(Javalin app, String basePath) {
        app.get(basePath + "/manifest", this::getManifest);
        app.get(basePath + "/list", this::listFiles);
        app.get(basePath + "/files/{path}", this::getFile);
    }

    private void listFiles(Context ctx) {
        String runId = ctx.queryParam("runId");
        String prefix = ctx.queryParam("prefix"); // Optional

        if (runId == null || runId.isBlank()) {
            ctx.status(400).result("Missing runId parameter");
            return;
        }

        try {
            List<String> files = storage.listAnalyticsFiles(runId, prefix != null ? prefix : "");
            ctx.json(files);
        } catch (Exception e) {
            log.warn("Failed to list analytics files for run {}", runId, e);
            ctx.status(500).result("Failed to list files");
        }
    }

    private void getManifest(Context ctx) {
        String runId = ctx.queryParam("runId");
        if (runId == null || runId.isBlank()) {
            ctx.status(400).result("Missing runId parameter");
            return;
        }

        // Check Cache
        CacheEntry entry = manifestCache.get(runId);
        if (entry != null && (System.currentTimeMillis() - entry.timestamp < cacheTtlMs)) {
            ctx.json(entry.json); // Sending string as JSON is safer via result + contentType
            ctx.contentType("application/json").result(entry.json);
            return;
        }

        // Rebuild Manifest
        try {
            List<String> files = storage.listAnalyticsFiles(runId, "");
            List<ManifestEntry> entries = new ArrayList<>();

            for (String file : files) {
                if (file.endsWith("metadata.json")) {
                    try (InputStream in = storage.openAnalyticsInputStream(runId, file)) {
                        String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        ManifestEntry manifestEntry = gson.fromJson(json, ManifestEntry.class);
                        entries.add(manifestEntry);
                    } catch (Exception e) {
                        log.warn("Failed to read/parse metadata file: {}", file, e);
                    }
                }
            }

            Map<String, Object> response = Map.of("metrics", entries);
            String responseJson = gson.toJson(response);
            
            // Update Cache
            manifestCache.put(runId, new CacheEntry(System.currentTimeMillis(), responseJson));
            
            ctx.json(response);
            
        } catch (Exception e) {
            log.error("Failed to aggregate manifest for run {}", runId, e);
            ctx.status(500).result("Failed to generate manifest");
        }
    }

    private void getFile(Context ctx) {
        String runId = ctx.queryParam("runId");
        String path = ctx.pathParam("path");

        if (runId == null || runId.isBlank()) {
            ctx.status(400).result("Missing runId parameter");
            return;
        }

        try {
            // Simple streaming
            InputStream in = storage.openAnalyticsInputStream(runId, path);
            ctx.result(in);
            
            // Set content type based on extension
            if (path.endsWith(".parquet")) {
                ctx.contentType("application/octet-stream");
            } else if (path.endsWith(".json")) {
                ctx.contentType("application/json");
            } else if (path.endsWith(".csv")) {
                ctx.contentType("text/csv");
            }
            
        } catch (Exception e) {
            log.warn("Failed to serve analytics file: {}/{}", runId, path);
            ctx.status(404).result("File not found");
        }
    }
}

