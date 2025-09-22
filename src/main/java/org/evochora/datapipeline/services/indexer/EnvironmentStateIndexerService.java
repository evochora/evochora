package org.evochora.datapipeline.services.indexer;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.channels.IInputChannel;
import org.evochora.datapipeline.api.contracts.RawTickData;
import org.evochora.datapipeline.api.contracts.RawCellState;
import org.evochora.datapipeline.api.contracts.SimulationContext;
import org.evochora.datapipeline.api.services.ServiceStatus;
import org.evochora.datapipeline.storage.api.indexer.IEnvironmentStateWriter;
import org.evochora.datapipeline.storage.api.indexer.model.EnvironmentState;
import org.evochora.datapipeline.storage.api.indexer.model.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service for indexing environment state data to persistent storage.
 * 
 * <p>This service listens to raw tick data messages and extracts environment
 * state information for cells that contain molecules or are owned by organisms.
 * The service implements batch processing with configurable batch size and timeout
 * to optimize database performance.</p>
 * 
 * <p>Configuration options:
 * <ul>
 *   <li>batchSize: Maximum number of environment states per batch (default: 1000)</li>
 *   <li>batchTimeoutMs: Maximum time to wait before flushing a batch (default: 5000ms)</li>
 *   <li>storageWriter: Configuration for the storage writer implementation</li>
 * </ul>
 * </p>
 * 
 * @author evochora
 * @since 1.0
 */
public class EnvironmentStateIndexerService extends org.evochora.datapipeline.services.AbstractService {
    
    private static final Logger log = LoggerFactory.getLogger(EnvironmentStateIndexerService.class);
    
    // Configuration
    private final int batchSize;
    private final long batchTimeoutMs;
    private final Config serviceConfig;
    
    // Batch processing
    private final List<EnvironmentState> currentBatch = new ArrayList<>();
    private final Object batchLock = new Object();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    // Storage
    private IEnvironmentStateWriter storageWriter;
    private volatile int environmentDimensions = 0;
    private volatile boolean storageInitialized = false;
    private volatile long lastProcessedTick = -1;
    
    // Channel management
    private final Map<String, IInputChannel<?>> inputChannels = new ConcurrentHashMap<>();
    private String tickDataChannelName;
    private String contextDataChannelName;
    private IInputChannel<?> rawTickDataChannel;
    private IInputChannel<?> contextChannel;
    
    /**
     * Creates a new EnvironmentStateIndexerService with the specified configuration.
     *
     * @param config The HOCON configuration options for this service
     */
    public EnvironmentStateIndexerService(Config config) {
        if (!config.hasPath("batchSize")) {
            log.error("Missing required configuration: batchSize. Cannot initialize EnvironmentStateIndexerService without batch size.");
            throw new IllegalArgumentException("batchSize configuration is required");
        }
        this.batchSize = config.getInt("batchSize");
        if (batchSize <= 0) {
            log.error("Invalid batchSize: {}. Must be greater than 0.", batchSize);
            throw new IllegalArgumentException("batchSize must be greater than 0, got: " + batchSize);
        }

        if (!config.hasPath("batchTimeoutMs")) {
            log.error("Missing required configuration: batchTimeoutMs. Cannot initialize EnvironmentStateIndexerService without batch timeout.");
            throw new IllegalArgumentException("batchTimeoutMs configuration is required");
        }
        this.batchTimeoutMs = config.getLong("batchTimeoutMs");
        if (batchTimeoutMs <= 0) {
            log.error("Invalid batchTimeoutMs: {}. Must be greater than 0.", batchTimeoutMs);
            throw new IllegalArgumentException("batchTimeoutMs must be greater than 0, got: " + batchTimeoutMs);
        }
        
        if (!config.hasPath("storage")) {
            log.error("Missing required configuration: storage. EnvironmentStateIndexerService requires storage configuration reference.");
            throw new IllegalArgumentException("storage configuration is required");
        }
        
        // Parse input channel mappings (similar to SimulationEngine outputs)
        if (config.hasPath("inputs")) {
            Config inputsConfig = config.getConfig("inputs");
            this.tickDataChannelName = inputsConfig.getString("tickData");
            this.contextDataChannelName = inputsConfig.getString("contextData");
        } else {
            throw new IllegalArgumentException("EnvironmentStateIndexerService requires 'inputs' configuration with 'tickData' and 'contextData' channel names");
        }
        
        this.serviceConfig = config; // The entire service configuration

        log.info("EnvironmentStateIndexerService initialized - batchSize: {}, batchTimeoutMs: {}ms, tickDataChannel: {}, contextDataChannel: {}",
            batchSize, batchTimeoutMs, tickDataChannelName, contextDataChannelName);
    }

    @Override
    public void addInputChannel(String channelKey, org.evochora.datapipeline.api.channels.IInputChannel<?> channel) {
        log.info("Added input channel: {} (type: {})", channelKey, channel.getClass().getSimpleName());

        // Call parent to add to channelBindings for status display
        super.addInputChannel(channelKey, channel);

        // Store channel for later use
        inputChannels.put(channelKey, channel);

        // Identify channel type based on the actual channel name (not the input key)
        if (channelKey.equals(tickDataChannelName)) {
            this.rawTickDataChannel = channel;
            log.info("Identified channel {} as RawTickData channel", channelKey);
        } else if (channelKey.equals(contextDataChannelName)) {
            this.contextChannel = channel;
            log.info("Identified channel {} as SimulationContext channel", channelKey);
        } else {
            log.warn("Unknown channel type for {} - expected '{}' or '{}'", 
                channelKey, tickDataChannelName, contextDataChannelName);
        }
    }

    @Override
    public void run() {
        log.info("EnvironmentStateIndexerService run() method started");
        try {
            // 1. Context lesen und verarbeiten - so lange bis es funktioniert
            log.info("Waiting for SimulationContext from contextChannel...");
            log.info("run() method - running.get(): {}, storageInitialized: {}", running.get(), storageInitialized);
            while (running.get() && !storageInitialized) {
                // Check for pause
                if (paused) {
                    synchronized (pauseLock) {
                        while (paused && running.get()) {
                            try {
                                pauseLock.wait();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                }
                
                log.debug("Reading from contextChannel...");
                Object contextObj = contextChannel.read();
                if (contextObj instanceof SimulationContext) {
                    processSimulationContext((SimulationContext) contextObj);
                } else {
                    log.warn("Expected SimulationContext but got: {}", contextObj.getClass().getSimpleName());
                }
            }
            
            // 2. Tick-Data verarbeiten - nur wenn Storage initialisiert ist
            log.debug("Storage initialized, now processing tick data from rawTickDataChannel...");
            while (running.get()) {
                // Check for pause BEFORE reading from channel
                if (paused) {
                    synchronized (pauseLock) {
                        while (paused && running.get()) {
                            try {
                                pauseLock.wait();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                }
                
                // Only read if not paused and still running
                if (!paused && running.get()) {
                    log.debug("Reading from rawTickDataChannel...");
                    Object tickDataObj = rawTickDataChannel.read();
                    if (tickDataObj instanceof RawTickData) {
                        processRawTickData((RawTickData) tickDataObj);
                    } else {
                        log.warn("Expected RawTickData but got: {}", tickDataObj.getClass().getSimpleName());
                    }
                }
                // Kein Thread.sleep() - read() blockiert bereits!
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("EnvironmentStateIndexerService interrupted");
        } catch (Exception e) {
            log.error("Error in EnvironmentStateIndexerService run loop", e);
            // Ensure resources are cleaned up even on unexpected exceptions
            try {
                flushBatch();
            } catch (Exception flushError) {
                log.error("Failed to flush batch during exception cleanup", flushError);
            }
        } finally {
            // Ensure service is marked as stopped
            running.set(false);
        }
    }

    @Override
    public void start() {
        log.info("Starting EnvironmentStateIndexerService");
        
        // Validate channels are available
        if (contextChannel == null) {
            log.error("Context channel not available - cannot determine environment dimensions");
            return;
        }
        
        if (rawTickDataChannel == null) {
            log.error("Raw tick data channel not available - cannot process tick data");
            return;
        }

        // Prevent double start
        if (running.get()) {
            log.warn("Service is already running - ignoring duplicate start() call");
            return;
        }

        // Call parent start() to handle state and thread creation FIRST
        super.start();
        
        // Set running flag to true after parent start() creates the thread
        running.set(true);
        
        // Schedule periodic batch flushing AFTER service is started
        scheduler.scheduleAtFixedRate(
            this::flushBatch,
            batchTimeoutMs,
            batchTimeoutMs,
            TimeUnit.MILLISECONDS
        );
    }

    @Override
    public void stop() {
        log.info("Stopping EnvironmentStateIndexerService");
        
        // Flush any remaining data
        flushBatch();
        
        // Shutdown scheduler
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Close storage writer
        if (storageWriter != null) {
            try {
                storageWriter.close();
                log.info("Storage writer closed successfully");
            } catch (Exception e) {
                log.error("Error closing storage writer", e);
            }
        }
        
        // Call parent stop() to handle state management and synchronization
        super.stop();
    }


    @Override
    public ServiceStatus getServiceStatus() {
        // Get base status from parent (includes channel binding statuses)
        ServiceStatus baseStatus = super.getServiceStatus();
        
        // Override state based on our running flag if needed
        // (The parent already handles currentState correctly)
        return baseStatus;
    }

    @Override
    public String getActivityInfo() {
        if (lastProcessedTick == -1) {
            return "No ticks processed yet";
        }
        return String.format("Last tick: %d", lastProcessedTick);
    }

    /**
     * Returns whether the storage has been initialized.
     * Used for testing purposes.
     */
    protected boolean isStorageInitialized() {
        return storageInitialized;
    }

    /**
     * Processes a simulation context message to extract environment dimensions.
     * This method initializes the storage writer once dimensions are known.
     *
     * @param context the simulation context containing environment information
     */
    private void processSimulationContext(SimulationContext context) {
        log.info("processSimulationContext called - storageInitialized: {}", storageInitialized);
        if (storageInitialized) {
            log.warn("SimulationContext received multiple times - storage already initialized, skipping duplicate context");
            return;
        }
        
        try {
            // Extract environment dimensions from context
            if (context.getEnvironment() == null || context.getEnvironment().getWorldShape() == null) {
                log.warn("SimulationContext has no environment or world shape information");
                return;
            }
            
            this.environmentDimensions = context.getEnvironment().getWorldShape().length;
            if (environmentDimensions <= 0) {
                log.warn("Invalid environment dimensions: {}", environmentDimensions);
                return;
            }
            
            log.info("Extracted environment dimensions: {} from SimulationContext", environmentDimensions);
            
            // Initialize storage with dimensions and simulation run ID
            initializeStorageWithDimensions(context.getSimulationRunId());
            
            // Verify storage was successfully initialized
            if (storageInitialized) {
                log.debug("Storage initialized successfully with {} dimensions", environmentDimensions);
            } else {
                log.warn("Storage initialization failed - will retry with next SimulationContext");
            }
        } catch (Exception e) {
            log.error("Failed to process SimulationContext: {} - will retry", e.getMessage(), e);
            storageInitialized = false; // Ensure we retry
            throw e; // Re-throw to make test failures visible
        }
    }

    /**
     * Initializes storage with known dimensions (called after receiving SimulationContext).
     */
    private void initializeStorageWithDimensions(String simulationRunId) {
        try {
            // Create storage writer from configuration
            storageWriter = createStorageWriter();
            
            // Initialize storage with dimensions and simulation run ID
            storageWriter.initialize(environmentDimensions, simulationRunId);
            
            storageInitialized = true;
            log.info("Storage writer initialized successfully with {} dimensions for simulation run: {}", 
                environmentDimensions, simulationRunId);
        } catch (Exception e) {
            log.error("Failed to initialize storage writer with {} dimensions for simulation run {}: {}", 
                environmentDimensions, simulationRunId, e.getMessage(), e);
            log.error("Storage writer initialization failed - full stack trace:", e);
            storageInitialized = false;
            throw new RuntimeException("Storage initialization failed", e);
        }
    }

    /**
     * Creates the storage writer from configuration.
     */
    protected IEnvironmentStateWriter createStorageWriter() {
        try {
            // Get storage configuration from service config
            String storageConfigName = serviceConfig.getString("storage");
            Config storageConfig = serviceConfig.getConfig("storageConfig").getConfig(storageConfigName);
            String className = storageConfig.getString("className");
            Config storageOptions = storageConfig.getConfig("options");
            
            log.debug("Creating storage writer: {} with options: {}", className, storageOptions);
            
            // Instantiate storage writer
            Class<?> clazz = Class.forName(className);
            Constructor<?> constructor = clazz.getConstructor(Config.class);
            return (IEnvironmentStateWriter) constructor.newInstance(storageOptions);
        } catch (Exception e) {
            log.error("Failed to create storage writer", e);
            throw new RuntimeException("Cannot create storage writer", e);
        }
    }

    /**
     * Processes raw tick data by extracting environment state information.
     */
    private void processRawTickData(RawTickData tickData) {
        if (!storageInitialized) {
            log.error("CRITICAL BUG: processRawTickData called before storage initialization! Data loss occurred!");
            throw new IllegalStateException("Cannot process tick data before storage is initialized");
        }
        
        // Update last processed tick
        lastProcessedTick = tickData.getTickNumber();
        
        List<EnvironmentState> states = extractEnvironmentStates(tickData);
        
        if (states.isEmpty()) {
            log.error("CRITICAL: No environment states extracted from tick data - data may be empty or invalid");
        }
        
        if (!states.isEmpty()) {
            boolean shouldFlush = false;
            synchronized (batchLock) {
                currentBatch.addAll(states);
                
                // Check if batch size reached
                if (currentBatch.size() >= batchSize) {
                    shouldFlush = true;
                }
            }
            
            // Flush outside of synchronized block to avoid deadlock
            if (shouldFlush) {
                flushBatch();
            }
        }
    }

    /**
     * Extracts environment state information from raw tick data.
     */
    private List<EnvironmentState> extractEnvironmentStates(RawTickData tickData) {
        List<EnvironmentState> states = new ArrayList<>();
        
        if (tickData.getCells() == null || tickData.getCells().isEmpty()) {
            return states;
        }
        
        for (RawCellState cell : tickData.getCells()) {
            // Only process cells that have molecules OR are owned by organisms
            // Exclude only cells where CODE=0 AND Owner=0
            boolean shouldExclude = (cell.getType() == org.evochora.runtime.Config.TYPE_CODE && 
                                    cell.getValue() == 0 && 
                                    cell.getOwnerId() == 0);
            
            if (!shouldExclude) {
                String moleculeType;
                try {
                    moleculeType = getMoleculeTypeName(cell.getType());
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown molecule type at position {} in tick {} with owner {}: {}", 
                        java.util.Arrays.toString(cell.getPosition()), tickData.getTickNumber(), cell.getOwnerId(), e.getMessage());
                    moleculeType = "UNKNOWN";
                }
                
                EnvironmentState state = new EnvironmentState(
                    tickData.getTickNumber(),
                    new Position(cell.getPosition()),
                    moleculeType,
                    cell.getValue(),
                    cell.getOwnerId()
                );
                states.add(state);
            }
        }
        
        return states;
    }

    /**
     * Converts molecule type ID to human-readable name.
     * 
     * @param type The molecule type ID
     * @return Human-readable name for the type
     * @throws IllegalArgumentException if the type is unknown
     */
    private String getMoleculeTypeName(int type) {
        return switch (type) {
            case org.evochora.runtime.Config.TYPE_CODE -> "CODE";
            case org.evochora.runtime.Config.TYPE_DATA -> "DATA";
            case org.evochora.runtime.Config.TYPE_ENERGY -> "ENERGY";
            case org.evochora.runtime.Config.TYPE_STRUCTURE -> "STRUCTURE";
            default -> throw new IllegalArgumentException("Unknown molecule type: " + type + " (0x" + Integer.toHexString(type) + ")");
        };
    }
    
    /**
     * Flushes the current batch to storage.
     */
    private void flushBatch() {
        if (currentBatch.isEmpty()) {
            return;
        }
        
        List<EnvironmentState> batchToFlush;
        synchronized (batchLock) {
            batchToFlush = new ArrayList<>(currentBatch);
            currentBatch.clear();
        }
        
        try {
            storageWriter.writeEnvironmentStates(batchToFlush);
            log.debug("Flushed batch of {} environment states", batchToFlush.size());
        } catch (Exception e) {
            log.error("Failed to flush batch of {} environment states", batchToFlush.size(), e);
        }
    }
}