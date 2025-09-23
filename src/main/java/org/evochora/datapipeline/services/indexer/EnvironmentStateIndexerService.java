package org.evochora.datapipeline.services.indexer;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.channels.IInputChannel;
import org.evochora.datapipeline.api.contracts.RawTickData;
import org.evochora.datapipeline.api.contracts.RawCellState;
import org.evochora.datapipeline.api.contracts.SimulationContext;
import org.evochora.datapipeline.api.services.State;
import org.evochora.datapipeline.api.services.ServiceStatus;
import org.evochora.datapipeline.core.InputChannelBinding;
import org.evochora.datapipeline.storage.api.indexer.IEnvironmentStateWriter;
import org.evochora.datapipeline.storage.api.indexer.model.EnvironmentState;
import org.evochora.datapipeline.storage.api.indexer.model.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
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
    private long lastFlushTime = System.currentTimeMillis();
    
    // Scheduler for batch timeout
    private ScheduledExecutorService scheduler;
    
    // Storage
    private IEnvironmentStateWriter storageWriter;
    private volatile int environmentDimensions = 0;
    private volatile boolean storageInitialized = false;
    private volatile long lastProcessedTick = -1;
    
    // Channel management
    
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
        
        this.serviceConfig = config; // The entire service configuration

        log.info("EnvironmentStateIndexerService initialized - batchSize: {}, batchTimeoutMs: {}ms",
            batchSize, batchTimeoutMs);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void addInputChannel(String portName, InputChannelBinding<?> binding) {
        registerInputChannel(portName, binding);
    }

    @Override
    public void run() {
        log.info("EnvironmentStateIndexerService run() method started");
        try {
            IInputChannel<RawTickData> tickDataChannel = getRequiredInputChannel("tickData");
            IInputChannel<SimulationContext> contextChannel = getRequiredInputChannel("contextData");

            log.info("Waiting for SimulationContext from contextChannel...");
            while (!storageInitialized && !Thread.currentThread().isInterrupted()) {
                synchronized (pauseLock) {
                    while (paused) {
                        pauseLock.wait();
                    }
                }
                try {
                    SimulationContext context = contextChannel.read();
                    if (context != null) {
                        processSimulationContext(context);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("Interrupted while waiting for SimulationContext.");
                    return; // Exit if interrupted
                }
            }
            
            log.debug("Storage initialized, now processing tick data...");
            while (!Thread.currentThread().isInterrupted()) {
                synchronized (pauseLock) {
                    while (paused) {
                        pauseLock.wait();
                    }
                }
                try {
                    RawTickData tickData = tickDataChannel.read(); // This will block if channel is empty
                    if (tickData != null) {
                        processRawTickData(tickData);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("Interrupted while waiting for RawTickData.");
                    return; // Exit if interrupted
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("EnvironmentStateIndexerService processing thread interrupted.");
        } catch (Exception e) {
            log.error("Unhandled exception in EnvironmentStateIndexerService run loop", e);
        } finally {
            // Final flush before shutting down
            log.info("Flushing final batch before shutdown...");
            flushBatchBlocking();
            log.info("EnvironmentStateIndexerService processing loop finished.");
        }
    }

    @Override
    public void start() {
        // The start logic is now fully handled by the AbstractService
        super.start();
        if (currentState.get() == State.RUNNING && (scheduler == null || scheduler.isShutdown())) {
            log.info("Starting batch flush scheduler with timeout {}ms.", batchTimeoutMs);
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleAtFixedRate(this::flushBatchByTimeout, batchTimeoutMs, batchTimeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void pause() {
        super.pause();
        if (scheduler != null && !scheduler.isShutdown()) {
            log.info("Shutting down batch flush scheduler due to service pause.");
            scheduler.shutdownNow();
        }
    }

    @Override
    public void resume() {
        super.resume();
        if (currentState.get() == State.RUNNING && (scheduler == null || scheduler.isShutdown())) {
            log.info("Resuming batch flush scheduler.");
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleAtFixedRate(this::flushBatchByTimeout, batchTimeoutMs, batchTimeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void stop() {
        log.info("Stopping EnvironmentStateIndexerService...");
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            log.info("Batch flush scheduler shut down.");
        }
        super.stop(); // This will interrupt the thread, causing the run() loop to exit.
        
        // Final flush is handled in the run() method's finally block.
        // Close the storage writer after the thread has finished.
        if (storageWriter != null) {
            try {
                storageWriter.close();
                log.info("Storage writer closed successfully.");
            } catch (Exception e) {
                log.error("Error closing storage writer", e);
            }
        }
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
            log.warn("Storage not initialized, skipping tick data processing for tick {}.", tickData.getTickNumber());
            return;
        }
        
        lastProcessedTick = tickData.getTickNumber();
        List<EnvironmentState> states = extractEnvironmentStates(tickData);
        
        synchronized (batchLock) {
            currentBatch.addAll(states);
            if (currentBatch.size() >= batchSize) {
                flushBatchBlocking();
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
     * Flushes the current batch to storage if the timeout has been reached.
     * This method is called by a scheduler.
     */
    private void flushBatchByTimeout() {
        try {
            synchronized (batchLock) {
                if (!currentBatch.isEmpty() && System.currentTimeMillis() - lastFlushTime > batchTimeoutMs) {
                    log.debug("Flushing batch of {} states due to timeout.", currentBatch.size());
                    flushBatchBlocking();
                }
            }
        } catch (Exception e) {
            // Prevent scheduler from dying on an unexpected error
            log.error("Error during scheduled batch flush", e);
        }
    }
    
    /**
     * Flushes the current batch to storage. This is a blocking operation.
     */
    private void flushBatchBlocking() {
        List<EnvironmentState> batchToFlush;
        synchronized (batchLock) {
            if (currentBatch.isEmpty()) {
                return;
            }
            batchToFlush = new ArrayList<>(currentBatch);
            currentBatch.clear();
            lastFlushTime = System.currentTimeMillis();
        }
        
        if (!batchToFlush.isEmpty()) {
            writeToDatabase(batchToFlush);
        }
    }

    /**
     * Writes a batch of environment states to the storage writer.
     * This method contains the actual blocking database call.
     * @param batch The list of states to write.
     */
    private void writeToDatabase(List<EnvironmentState> batch) {
        try {
            storageWriter.writeEnvironmentStates(batch);
            log.debug("Flushed batch of {} environment states", batch.size());
        } catch (Exception e) {
            log.error("Failed to flush batch of {} environment states", batch.size(), e);
        }
    }
    
    private Thread findServiceThread() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals(this.getClass().getSimpleName().toLowerCase())) {
                return t;
            }
        }
        return null;
    }
}