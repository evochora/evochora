package org.evochora.datapipeline.services.indexer;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.RawTickData;
import org.evochora.datapipeline.api.contracts.RawCellState;
import org.evochora.datapipeline.api.services.State;
import org.evochora.datapipeline.storage.api.indexer.IEnvironmentStateWriter;
import org.evochora.datapipeline.storage.api.indexer.model.EnvironmentState;
import org.evochora.datapipeline.storage.api.indexer.model.Position;
import org.evochora.datapipeline.storage.impl.h2.H2SimulationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service for indexing environment state data to persistent storage.
 * 
 * <p>This service listens to simulation context messages and extracts environment
 * state information for cells that contain molecules or are owned by organisms.
 * The service implements batch processing with configurable batch size and timeout
 * to optimize database performance.</p>
 * 
 * <p>Configuration options:
 * <ul>
 *   <li>batchSize: Maximum number of environment states per batch (default: 1000)</li>
 *   <li>batchTimeoutMs: Maximum time to wait before flushing a batch (default: 5000ms)</li>
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
    private final IEnvironmentStateWriter storageWriter;
    
    // Batch processing
    private final List<EnvironmentState> currentBatch = new ArrayList<>();
    private final Object batchLock = new Object();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean batchScheduled = new AtomicBoolean(false);
    
    // Environment properties
    private int environmentDimensions = 0;
    private boolean initialized = false;
    
    /**
     * Creates a new EnvironmentStateIndexerService with the specified configuration.
     * 
     * @param options Configuration options from the pipeline config
     */
    public EnvironmentStateIndexerService(Config options) {
        // Read from options sub-config
        Config serviceOptions = options.hasPath("options") ? options.getConfig("options") : options;
        
        this.batchSize = serviceOptions.hasPath("batchSize") ? serviceOptions.getInt("batchSize") : 1000;
        this.batchTimeoutMs = serviceOptions.hasPath("batchTimeoutMs") ? serviceOptions.getLong("batchTimeoutMs") : 5000L;
        
        // Initialize storage writer (for now, hardcoded to H2 - later this will be injected)
        this.storageWriter = createStorageWriter(serviceOptions);
        
        log.info("EnvironmentStateIndexerService initialized - batchSize: {}, batchTimeoutMs: {}ms", 
                batchSize, batchTimeoutMs);
    }
    
    @Override
    public void run() {
        try {
            log.info("Started EnvironmentStateIndexerService");
            currentState.set(State.RUNNING);
            
            // Main processing loop
            while (currentState.get() == State.RUNNING && !Thread.currentThread().isInterrupted()) {
                try {
                    // Wait for messages or timeout
                    Thread.sleep(100); // Small delay to prevent busy waiting
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            
        } catch (Exception e) {
            log.error("EnvironmentStateIndexerService failed: {}", e.getMessage(), e);
            currentState.set(State.STOPPED);
        } finally {
            // Flush any remaining data
            flushBatch();
            scheduler.shutdown();
            log.info("EnvironmentStateIndexerService stopped");
        }
    }
    
    @Override
    public void addInputChannel(String name, org.evochora.datapipeline.api.channels.IInputChannel<?> channel) {
        super.addInputChannel(name, channel);
        
        if ("raw-tick-channel".equals(name)) {
            // Start listening to raw tick data messages
            startListeningToRawTickData((org.evochora.datapipeline.api.channels.IInputChannel<RawTickData>) channel);
        }
    }
    
    /**
     * Starts listening to raw tick data messages from the specified channel.
     */
    private void startListeningToRawTickData(org.evochora.datapipeline.api.channels.IInputChannel<RawTickData> channel) {
        // This would typically be implemented with a proper message listener
        // For now, this is a placeholder for the actual implementation
        log.info("Started listening to raw-tick-channel");
    }
    
    /**
     * Processes a raw tick data message and extracts environment state data.
     * This method should be called by the message processing system.
     * 
     * @param tickData the raw tick data to process
     */
    public void processRawTickData(RawTickData tickData) {
        try {
            // Initialize storage writer with environment dimensions if not done yet
            if (!initialized) {
                initializeStorage(tickData);
            }
            
            // Extract environment states from the tick data
            List<EnvironmentState> states = extractEnvironmentStates(tickData);
            
            if (!states.isEmpty()) {
                addToBatch(states);
            }
            
        } catch (Exception e) {
            log.error("Failed to process raw tick data: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Initializes the storage writer with environment dimensions.
     * For now, we assume 2D environment - this should be passed from configuration.
     */
    private void initializeStorage(RawTickData tickData) {
        // For now, assume 2D environment - this should be passed from configuration
        environmentDimensions = 2;
        storageWriter.initialize(environmentDimensions);
        initialized = true;
        log.info("Storage initialized with {} dimensions", environmentDimensions);
    }
    
    /**
     * Extracts environment state data from raw tick data.
     * Only includes cells that have molecules or are owned by organisms.
     */
    private List<EnvironmentState> extractEnvironmentStates(RawTickData tickData) {
        List<EnvironmentState> states = new ArrayList<>();
        
        if (tickData.getCells() == null) {
            return states;
        }
        
        long tick = tickData.getTickNumber();
        
        for (RawCellState cell : tickData.getCells()) {
            // Only process cells that have molecules or are owned
            if (cell.getValue() > 0) {
                // Cell has a molecule
                states.add(new EnvironmentState(
                    tick,
                    new Position(cell.getPosition()),
                    String.valueOf(cell.getType()),
                    cell.getValue(),
                    cell.getOwnerId()
                ));
            } else if (cell.getOwnerId() > 0) {
                // Cell is owned but has no molecule
                states.add(new EnvironmentState(
                    tick,
                    new Position(cell.getPosition()),
                    "EMPTY", // Placeholder for owned but empty cells
                    0,
                    cell.getOwnerId()
                ));
            }
        }
        
        return states;
    }
    
    /**
     * Adds environment states to the current batch and triggers flush if needed.
     */
    private void addToBatch(List<EnvironmentState> states) {
        synchronized (batchLock) {
            currentBatch.addAll(states);
            
            // Check if batch is full
            if (currentBatch.size() >= batchSize) {
                flushBatch();
            } else if (!batchScheduled.get()) {
                // Schedule timeout flush
                scheduleBatchFlush();
            }
        }
    }
    
    /**
     * Schedules a batch flush after the configured timeout.
     */
    private void scheduleBatchFlush() {
        if (batchScheduled.compareAndSet(false, true)) {
            scheduler.schedule(() -> {
                synchronized (batchLock) {
                    if (!currentBatch.isEmpty()) {
                        flushBatch();
                    }
                    batchScheduled.set(false);
                }
            }, batchTimeoutMs, TimeUnit.MILLISECONDS);
        }
    }
    
    /**
     * Flushes the current batch to storage.
     */
    private void flushBatch() {
        List<EnvironmentState> batchToFlush;
        
        synchronized (batchLock) {
            if (currentBatch.isEmpty()) {
                return;
            }
            
            batchToFlush = new ArrayList<>(currentBatch);
            currentBatch.clear();
            batchScheduled.set(false);
        }
        
        try {
            storageWriter.writeEnvironmentStates(batchToFlush);
            log.debug("Flushed batch of {} environment states", batchToFlush.size());
        } catch (Exception e) {
            log.error("Failed to flush batch: {}", e.getMessage(), e);
        }
    }
    
    @Override
    public String getActivityInfo() {
        synchronized (batchLock) {
            return String.format("Batch: %d/%d", currentBatch.size(), batchSize);
        }
    }
    
    /**
     * Creates the storage writer instance. This method is protected to allow
     * testing with mocked implementations.
     * 
     * @param config the configuration for the storage writer
     * @return the storage writer instance
     */
    protected IEnvironmentStateWriter createStorageWriter(Config config) {
        return new H2SimulationRepository(config);
    }
}
