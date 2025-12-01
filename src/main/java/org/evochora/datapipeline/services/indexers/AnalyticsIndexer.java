package org.evochora.datapipeline.services.indexers;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.evochora.datapipeline.api.analytics.IAnalyticsContext;
import org.evochora.datapipeline.api.analytics.IAnalyticsPlugin;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.memory.IMemoryEstimatable;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.storage.IAnalyticsStorageWrite;
import org.evochora.datapipeline.utils.PathExpansion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigObject;

/**
 * Indexer service for generating analytics artifacts.
 * <p>
 * Loads configured plugins and delegates batch processing to them.
 * Handles lifecycle, metadata aggregation, and error resilience (Bulkhead Pattern).
 */
public class AnalyticsIndexer<ACK> extends AbstractBatchIndexer<ACK> implements IMemoryEstimatable {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsIndexer.class);

    private final IAnalyticsStorageWrite analyticsOutput;
    private final List<IAnalyticsPlugin> plugins = new ArrayList<>();
    private final Path tempDirectory;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final int insertBatchSize;
    
    // Metrics
    private final AtomicLong ticksProcessed = new AtomicLong(0);

    public AnalyticsIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.analyticsOutput = getRequiredResource("analyticsOutput", IAnalyticsStorageWrite.class);
        this.insertBatchSize = options.hasPath("insertBatchSize") ? options.getInt("insertBatchSize") : 25;
        
        // Configure temp directory
        String tempPathStr = options.hasPath("tempDirectory") 
            ? options.getString("tempDirectory") 
            : System.getProperty("java.io.tmpdir") + "/evochora/analytics/" + name;
        
        String expandedPath = PathExpansion.expandPath(tempPathStr);
        this.tempDirectory = Paths.get(expandedPath);
        
        // Load plugins
        loadPlugins(options);
    }

    private void loadPlugins(Config options) {
        if (!options.hasPath("plugins")) {
            log.warn("No plugins configured for AnalyticsIndexer '{}'", serviceName);
            return;
        }

        List<? extends ConfigObject> pluginConfigs = options.getObjectList("plugins");
        for (ConfigObject co : pluginConfigs) {
            Config pluginConfig = co.toConfig();
            String className = pluginConfig.getString("className");
            Config pluginOptions = pluginConfig.hasPath("options") 
                ? pluginConfig.getConfig("options") 
                : com.typesafe.config.ConfigFactory.empty();

            try {
                Class<?> clazz = Class.forName(className);
                if (!IAnalyticsPlugin.class.isAssignableFrom(clazz)) {
                    throw new IllegalArgumentException("Class " + className + " does not implement IAnalyticsPlugin");
                }
                
                IAnalyticsPlugin plugin = (IAnalyticsPlugin) clazz.getDeclaredConstructor().newInstance();
                plugin.configure(pluginOptions);
                plugins.add(plugin);
                log.info("Loaded analytics plugin: {}", className);
                
            } catch (Exception e) {
                // Fatal error on startup if config is wrong
                throw new RuntimeException("Failed to load plugin: " + className, e);
            }
        }
    }

    @Override
    protected Set<ComponentType> getRequiredComponents() {
        // Needs metadata for plugins and buffering for efficiency
        return EnumSet.of(ComponentType.METADATA, ComponentType.BUFFERING);
    }
    
    @Override
    protected Set<ComponentType> getOptionalComponents() {
        return EnumSet.of(ComponentType.DLQ); // Optional DLQ support
    }

    @Override
    protected void prepareTables(String runId) throws Exception {
        // 1. Startup Cleanup (Resilience)
        cleanupTempDirectory();
        Files.createDirectories(tempDirectory);
        
        // 2. Initialize Plugins
        IAnalyticsContext context = new AnalyticsContextImpl(runId);
        for (IAnalyticsPlugin plugin : plugins) {
            try {
                plugin.initialize(context);
                
                // Write manifest immediately (idempotent)
                writePluginMetadata(runId, plugin);
                
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize plugin", e);
            }
        }
        
        log.debug("AnalyticsIndexer prepared for run: {}", runId);
    }

    @Override
    protected void onShutdown() throws Exception {
        // 1. Call onFinish() for all plugins
        for (IAnalyticsPlugin plugin : plugins) {
            try {
                plugin.onFinish();
            } catch (Exception e) {
                log.warn("Plugin {} failed during onFinish(): {}", 
                    plugin.getClass().getSimpleName(), e.getMessage());
                log.debug("Plugin onFinish() error details:", e);
                // Continue with other plugins (bulkhead pattern)
            }
        }
        
        // 2. Clean up temp directory
        cleanupTempDirectory();
        
        log.debug("AnalyticsIndexer shutdown cleanup completed");
    }

    @Override
    protected void flushTicks(List<TickData> ticks) throws Exception {
        if (ticks.isEmpty()) return;
        
        boolean anyIoError = false;
        
        for (IAnalyticsPlugin plugin : plugins) {
            try {
                plugin.processBatch(ticks);
            } catch (RuntimeException e) {
                // Logic error (Bug) -> Bulkhead: Log and continue other plugins
                log.error("Plugin failed for batch (skipping this plugin for this batch): {}", e.getMessage());
                // Debug log for stacktrace
                log.debug("Plugin failure details:", e);
            } catch (Exception e) {
                // IO Error (or checked exception) -> Fatal for batch integrity
                log.warn("Plugin IO failure: {}", e.getMessage());
                anyIoError = true;
            }
        }
        
        ticksProcessed.addAndGet(ticks.size());
        
        if (anyIoError) {
            throw new IOException("One or more plugins failed with IO error. Failing batch.");
        }
    }
    
    private void cleanupTempDirectory() {
        try {
            if (Files.exists(tempDirectory)) {
                try (var stream = Files.walk(tempDirectory)) {
                     stream.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
                }
                log.debug("Cleaned up temp directory: {}", tempDirectory);
            }
        } catch (IOException e) {
            log.warn("Failed to clean temp directory on startup: {}", e.getMessage());
        }
    }
    
    private void writePluginMetadata(String runId, IAnalyticsPlugin plugin) {
        ManifestEntry entry = plugin.getManifestEntry();
        if (entry == null) return;
        
        try {
            String json = gson.toJson(entry);
            // Write to {metricId}/metadata.json
            analyticsOutput.writeAnalyticsBlob(
                runId, 
                entry.id, 
                null, 
                "metadata.json", 
                json.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            log.warn("Failed to write metadata for plugin {}: {}", entry.id, e.getMessage());
            // Non-fatal? If metadata is missing, frontend won't see it. 
            // We allow startup to proceed.
        }
    }

    /**
     * Context implementation passed to plugins.
     */
    private class AnalyticsContextImpl implements IAnalyticsContext {
        private final String runId;

        public AnalyticsContextImpl(String runId) {
            this.runId = runId;
        }

        @Override
        public SimulationMetadata getMetadata() {
            // Access protected method from AbstractBatchIndexer
            return AnalyticsIndexer.this.getMetadata(); 
        }

        @Override
        public String getRunId() {
            return runId;
        }

        @Override
        public OutputStream openArtifactStream(String metricId, String lodLevel, String filename) throws IOException {
            return analyticsOutput.openAnalyticsOutputStream(runId, metricId, lodLevel, filename);
        }

        @Override
        public Path getTempDirectory() {
            return AnalyticsIndexer.this.tempDirectory;
        }
    }
    
    // ==================== IMemoryEstimatable ====================
    
    /**
     * {@inheritDoc}
     * <p>
     * Estimates memory for the AnalyticsIndexer tick buffer at worst-case.
     * <p>
     * AnalyticsIndexer buffers ticks for plugin processing. The memory usage
     * depends on insertBatchSize and the size of each tick (environment + organisms).
     */
    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // Each tick contains full environment + organisms data
        long bytesPerTick = params.estimateBytesPerTick();
        long totalBytes = (long) insertBatchSize * bytesPerTick;
        
        // Add TickData wrapper overhead (~200 bytes per tick)
        long wrapperOverhead = (long) insertBatchSize * 200;
        totalBytes += wrapperOverhead;
        
        // Add plugin internal state (~1MB per plugin as conservative estimate)
        long pluginOverhead = (long) plugins.size() * 1024 * 1024;
        totalBytes += pluginOverhead;
        
        String explanation = String.format("%d insertBatchSize × %s/tick + %d plugins overhead",
            insertBatchSize,
            SimulationParameters.formatBytes(bytesPerTick),
            plugins.size());
        
        return List.of(new MemoryEstimate(
            serviceName,
            totalBytes,
            explanation,
            MemoryEstimate.Category.SERVICE_BATCH
        ));
    }
}
