package org.evochora.datapipeline.services.debugindexer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.datapipeline.api.channels.IInputChannel;
import org.evochora.datapipeline.api.contracts.SimulationContext;
import org.evochora.datapipeline.api.contracts.RawTickData;
import org.evochora.datapipeline.api.services.State;
import org.evochora.datapipeline.services.AbstractService;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.server.config.SimulationConfiguration;
import org.evochora.server.contracts.debug.PreparedTickState;
import org.evochora.server.contracts.raw.RawTickState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

/**
 * Debug indexer service that reads from pipeline channels and writes to debug SQLite database.
 * This service bridges the data pipeline architecture with the existing debug infrastructure.
 * 
 * <p>Key features:</p>
 * <ul>
 *   <li>Reads SimulationContext messages from configurable input channel</li>
 *   <li>Processes RawTickState data using existing indexer logic</li>
 *   <li>Writes prepared debug data directly to SQLite database</li>
 *   <li>Compatible with existing web debugger interface</li>
 * </ul>
 */
public class DebugIndexerService extends AbstractService implements Runnable {
    
    private static final Logger log = LoggerFactory.getLogger(DebugIndexerService.class);
    
    // Configuration
    private final String debugDbPath;
    private final int batchSize;
    private final boolean enabled;
    private final SimulationConfiguration.DatabaseConfig databaseConfig;
    private final SimulationConfiguration.MemoryOptimizationConfig memoryOptimizationConfig;
    
    // Input channel - accepts both SimulationContext and RawTickData
    private IInputChannel<Object> inputChannel;
    
    // Processing components
    private final ObjectMapper objectMapper;
    private final DatabaseManager databaseManager;
    private final TickProcessor tickProcessor;
    private final ArtifactValidator artifactValidator;
    private final SourceViewBuilder sourceViewBuilder;
    private final InstructionBuilder instructionBuilder;
    private final InternalStateBuilder internalStateBuilder;
    
    // Runtime state
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, ProgramArtifact> programArtifacts = new ConcurrentHashMap<>();
    private EnvironmentProperties environmentProperties;
    private Thread processingThread;
    
    // Batch processing
    private final Object batchLock = new Object();
    private volatile long lastProcessedTick = -1;
    
    /**
     * Creates a new DebugIndexerService with the specified configuration.
     * 
     * @param options Configuration options from the pipeline config
     */
    public DebugIndexerService(Config options) {
        // Read from options sub-config
        Config serviceOptions = options.hasPath("options") ? options.getConfig("options") : options;
        
        // Generate unique debug database filename with timestamp
        String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        this.debugDbPath = serviceOptions.hasPath("debugDbPath") ? serviceOptions.getString("debugDbPath") : "runs/sim_run_" + timestamp + "_debug.sqlite";
        this.batchSize = serviceOptions.hasPath("batchSize") ? serviceOptions.getInt("batchSize") : 100;
        this.enabled = serviceOptions.hasPath("enabled") ? serviceOptions.getBoolean("enabled") : true;
        
        // Parse database config if available
        this.databaseConfig = serviceOptions.hasPath("database") 
            ? parseDatabaseConfig(serviceOptions.getConfig("database"))
            : new SimulationConfiguration.DatabaseConfig();
            
        // Parse memory optimization config if available
        this.memoryOptimizationConfig = serviceOptions.hasPath("memoryOptimization")
            ? parseMemoryOptimizationConfig(serviceOptions.getConfig("memoryOptimization"))
            : new SimulationConfiguration.MemoryOptimizationConfig();
        
        // Initialize Jackson ObjectMapper
        this.objectMapper = new ObjectMapper();
        
        // Initialize processing components
        this.databaseManager = new DatabaseManager(debugDbPath, batchSize, this.databaseConfig);
        this.artifactValidator = new ArtifactValidator();
        this.sourceViewBuilder = new SourceViewBuilder();
        this.instructionBuilder = new InstructionBuilder();
        this.internalStateBuilder = new InternalStateBuilder();
        this.tickProcessor = new TickProcessor(
            objectMapper, 
            artifactValidator, 
            sourceViewBuilder, 
            instructionBuilder, 
            internalStateBuilder, 
            this.memoryOptimizationConfig
        );
        
        log.info("DebugIndexerService initialized - debugDbPath: {}, batchSize: {}, enabled: {}", 
                debugDbPath, batchSize, enabled);
    }
    
    /**
     * Adds an input channel to this service.
     * 
     * @param name The channel name
     * @param channel The input channel for SimulationContext and RawTickData messages
     */
    @Override
    @SuppressWarnings("unchecked")
    public void addInputChannel(String name, IInputChannel<?> channel) {
        // Call parent method to store channel for dynamic status determination
        super.addInputChannel(name, channel);
        
        // Store channel for this service's specific use
        this.inputChannel = (IInputChannel<Object>) channel;
        log.debug("Input channel '{}' added to DebugIndexerService: {}", name, channel.getClass().getSimpleName());
    }
    
    @Override
    public void run() {
        try {
            startService();
        } catch (Exception e) {
            log.error("Failed to start DebugIndexerService: {}", e.getMessage(), e);
            return;
        }
        processMessages();
    }
    
    protected void startService() throws Exception {
        if (!enabled) {
            log.info("DebugIndexerService is disabled, skipping startup");
            return;
        }
        
        if (inputChannel == null) {
            throw new IllegalStateException("No input channel configured for DebugIndexerService");
        }
        
        log.debug("DebugIndexerService: Input channel available: {} (type: {})", 
                inputChannel.getClass().getSimpleName(), 
                inputChannel.getClass().getGenericInterfaces()[0].getTypeName());
        
        // Ensure database connection
        if (!databaseManager.ensureConnection()) {
            throw new IllegalStateException("Failed to establish database connection");
        }
        
        // Add shutdown hook to ensure WAL commit on JVM shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (databaseManager != null) {
                log.debug("Shutdown hook: committing WAL and closing database");
                databaseManager.flush();
                databaseManager.commitWAL();
                databaseManager.closeQuietly();
            }
        }));
        
        // Start processing thread
        running.set(true);
        processingThread = new Thread(this::processMessages, "DebugIndexerService");
        processingThread.start();
        
        log.info("DebugIndexerService started with input channel: {} (running: {})", 
                inputChannel.getClass().getSimpleName(), running.get());
    }
    
    protected void pauseService() throws Exception {
        // Flush any pending database operations and commit WAL
        if (databaseManager != null) {
            databaseManager.flush();
            databaseManager.commitWAL();
        }
        log.info("DebugIndexerService paused");
    }
    
    protected void resumeService() throws Exception {
        log.info("DebugIndexerService resumed");
    }
    
    protected void stopService() throws Exception {
        log.info("Stopping DebugIndexerService...");
        
        // First set running to false to stop new message processing
        running.set(false);
        
        // Interrupt the processing thread to wake it up from blocking read
        if (processingThread != null) {
            processingThread.interrupt();
            
            // Wait for the thread to actually stop with multiple attempts
            int attempts = 0;
            while (processingThread.isAlive() && attempts < 10) {
                try {
                    processingThread.join(500); // Wait 500ms at a time
                    attempts++;
                } catch (InterruptedException e) {
                    log.warn("Interrupted while waiting for processing thread to stop");
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            
            if (processingThread.isAlive()) {
                log.warn("Processing thread did not stop within 5 seconds, it may still be processing");
            } else {
                log.debug("Processing thread stopped successfully");
            }
        }
        
        // Additional safety: wait a bit more to ensure all processing is complete
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Now it's safe to close the database
        if (databaseManager != null) {
            log.debug("Flushing and closing database...");
            databaseManager.flush();
            databaseManager.commitWAL();
            databaseManager.close();
        }
        
        log.info("DebugIndexerService stopped");
    }
    
    /**
     * Gets whether the service is enabled.
     * 
     * @return true if enabled, false otherwise
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Gets the debug database path.
     * 
     * @return The debug database path
     */
    public String getDebugDbPath() {
        return debugDbPath;
    }
    
    /**
     * Gets the batch size for processing.
     * 
     * @return The batch size
     */
    public int getBatchSize() {
        return batchSize;
    }
    
    /**
     * Gets whether the service is running.
     * 
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return currentState.get() == State.RUNNING;
    }
    
    /**
     * Gets whether the service is paused.
     * 
     * @return true if paused, false otherwise
     */
    public boolean isPaused() {
        return currentState.get() == State.PAUSED;
    }
    
    /**
     * Gets whether the service is stopped.
     * 
     * @return true if stopped, false otherwise
     */
    public boolean isStopped() {
        return currentState.get() == State.STOPPED;
    }
    
    /**
     * Gets the last processed tick number.
     * 
     * @return the last processed tick number, or -1 if none processed yet
     */
    public long getLastProcessedTick() {
        return lastProcessedTick;
    }
    
    /**
     * Gets activity information for status display.
     * 
     * @return activity information string
     */
    @Override
    public String getActivityInfo() {
        if (lastProcessedTick >= 0) {
            return String.format("Last tick: %d", lastProcessedTick);
        } else {
            return "No ticks processed yet";
        }
    }
    
    /**
     * Main processing loop that reads messages from the input channel and processes them in batches.
     */
    private void processMessages() {
        log.debug("DebugIndexerService processing thread started");
        
        int totalMessageCount = 0;
        while (currentState.get() == State.RUNNING && !Thread.currentThread().isInterrupted() && running.get()) {
            try {
                // Check if database is still available before processing
                if (databaseManager == null || !databaseManager.isConnectionAvailable()) {
                    log.debug("Database not available, stopping processing");
                    break;
                }
                
                // Collect messages into batch
                List<Object> currentBatch = new ArrayList<>();
                synchronized (batchLock) {
                    // Read up to batchSize messages
                    for (int i = 0; i < batchSize; i++) {
                        // Double-check running state before each read
                        if (!running.get() || currentState.get() != State.RUNNING || Thread.currentThread().isInterrupted()) {
                            log.debug("Stopping message collection due to shutdown signal");
                            break;
                        }
                        
                        try {
                            Object message = inputChannel.read();
                            if (message != null) {
                                currentBatch.add(message);
                            } else {
                                // No more messages available right now
                                break;
                            }
                        } catch (InterruptedException e) {
                            log.debug("Interrupted during message read, stopping");
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                
                // Process batch if we have messages and still running
                if (!currentBatch.isEmpty() && running.get() && currentState.get() == State.RUNNING) {
                    processBatch(currentBatch);
                    totalMessageCount += currentBatch.size();
                    
                    if (totalMessageCount % 1000 == 0) {
                        log.debug("DebugIndexerService processed {} messages total", totalMessageCount);
                    }
                } else if (currentBatch.isEmpty()) {
                    // No messages available, yield to prevent busy waiting
                    Thread.yield();
                }
            } catch (Exception e) {
                log.error("Error processing messages in DebugIndexerService: {}", e.getMessage(), e);
                // Continue processing other messages
            }
        }
        
        log.debug("DebugIndexerService processing thread stopped after processing {} messages", totalMessageCount);
    }
    
    /**
     * Processes a batch of messages from the input channel.
     * 
     * @param messages The batch of messages to process
     */
    private void processBatch(List<Object> messages) {
        try {
            for (Object message : messages) {
                // Check if we should stop processing
                if (!running.get() || currentState.get() != State.RUNNING) {
                    log.debug("Stopping batch processing due to shutdown signal");
                    break;
                }
                
                if (message instanceof SimulationContext context) {
                    processSimulationContext(context);
                } else if (message instanceof RawTickData tickData) {
                    processRawTickData(tickData);
                } else {
                    log.warn("Unknown message type received: {}", message.getClass().getName());
                }
            }
        } catch (Exception e) {
            log.error("Error processing message batch: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Processes a single SimulationContext message.
     * 
     * @param context The simulation context to process
     */
    private void processSimulationContext(SimulationContext context) {
        try {
            // Update environment properties if available
            if (context.getEnvironment() != null) {
                // Convert from datapipeline EnvironmentProperties to runtime EnvironmentProperties
                this.environmentProperties = new org.evochora.runtime.model.EnvironmentProperties(
                    context.getEnvironment().getWorldShape(),
                    context.getEnvironment().getTopology() == org.evochora.datapipeline.api.contracts.WorldTopology.TORUS
                );
            }
            
            // Update program artifacts if available
            if (context.getArtifacts() != null) {
                for (org.evochora.datapipeline.api.contracts.ProgramArtifact apiArtifact : context.getArtifacts()) {
                    ProgramArtifact compilerArtifact = convertToCompilerArtifact(apiArtifact);
                    programArtifacts.put(apiArtifact.getProgramId(), compilerArtifact);
                }
                log.debug("Converted {} program artifacts from SimulationContext", context.getArtifacts().size());
            }
            
            log.debug("Updated SimulationContext - environment: {}, artifacts: {}", 
                     environmentProperties != null, programArtifacts.size());
            
        } catch (Exception e) {
            log.error("Error processing SimulationContext: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Processes a single RawTickData message.
     * 
     * @param tickData The raw tick data to process
     */
    private void processRawTickData(RawTickData tickData) {
        try {
            // Check if we should stop processing
            if (!running.get() || currentState.get() != State.RUNNING) {
                log.debug("Skipping tick {} due to shutdown signal", tickData.getTickNumber());
                return;
            }
            
            // Convert RawTickData to RawTickState
            RawTickState rawTickState = convertToRawTickState(tickData);
            
            // Process using existing logic
            processRawTickState(rawTickState);
            
        } catch (Exception e) {
            log.error("Error processing RawTickData for tick {}: {}", tickData.getTickNumber(), e.getMessage(), e);
        }
    }
    
    /**
     * Converts RawTickData (API contract) to RawTickState (server contract).
     * 
     * @param tickData The RawTickData to convert
     * @return The converted RawTickState
     */
    private RawTickState convertToRawTickState(RawTickData tickData) {
        // Convert cells
        List<org.evochora.server.contracts.raw.RawCellState> serverCells = new ArrayList<>();
        if (tickData.getCells() != null) {
            for (org.evochora.datapipeline.api.contracts.RawCellState apiCell : tickData.getCells()) {
                serverCells.add(convertCellState(apiCell));
            }
        }
        
        // Convert organisms
        List<org.evochora.server.contracts.raw.RawOrganismState> serverOrganisms = new ArrayList<>();
        if (tickData.getOrganisms() != null) {
            for (org.evochora.datapipeline.api.contracts.RawOrganismState apiOrganism : tickData.getOrganisms()) {
                serverOrganisms.add(convertOrganismState(apiOrganism));
            }
        }
        
        return new RawTickState(
            tickData.getTickNumber(),
            serverOrganisms,
            serverCells
        );
    }
    
    /**
     * Converts API RawCellState to server RawCellState.
     */
    private org.evochora.server.contracts.raw.RawCellState convertCellState(
            org.evochora.datapipeline.api.contracts.RawCellState apiCell) {
        return new org.evochora.server.contracts.raw.RawCellState(
            apiCell.getPosition(),
            apiCell.getType(),
            apiCell.getOwnerId()
        );
    }
    
    /**
     * Converts API RawOrganismState to server RawOrganismState.
     */
    private org.evochora.server.contracts.raw.RawOrganismState convertOrganismState(
            org.evochora.datapipeline.api.contracts.RawOrganismState apiOrganism) {
        
        // Convert register arrays
        List<Object> dataRegisters = new ArrayList<>();
        if (apiOrganism.getDataRegisters() != null) {
            for (int value : apiOrganism.getDataRegisters()) {
                dataRegisters.add(value);
            }
        }
        
        List<Object> procedureRegisters = new ArrayList<>();
        if (apiOrganism.getProcedureRegisters() != null) {
            for (int value : apiOrganism.getProcedureRegisters()) {
                procedureRegisters.add(value);
            }
        }
        
        List<Object> fpRegisters = new ArrayList<>();
        if (apiOrganism.getFormalParamRegisters() != null) {
            for (int value : apiOrganism.getFormalParamRegisters()) {
                fpRegisters.add(value);
            }
        }
        
        List<Object> locationRegisters = new ArrayList<>();
        if (apiOrganism.getLocationRegisters() != null) {
            for (int[] coords : apiOrganism.getLocationRegisters()) {
                locationRegisters.add(coords);
            }
        }
        
        // Convert stacks
        List<Object> dataStack = new ArrayList<>();
        if (apiOrganism.getDataStack() != null) {
            for (org.evochora.datapipeline.api.contracts.StackValue stackValue : apiOrganism.getDataStack()) {
                // Convert StackValue to appropriate type
                if (stackValue.getType() == org.evochora.datapipeline.api.contracts.StackValueType.LITERAL) {
                    dataStack.add(stackValue.getLiteralValue());
                } else if (stackValue.getType() == org.evochora.datapipeline.api.contracts.StackValueType.POSITION) {
                    dataStack.add(stackValue.getPositionValue());
                } else {
                    dataStack.add(0); // Default fallback
                }
            }
        }
        
        List<int[]> locationStack = new ArrayList<>();
        if (apiOrganism.getLocationStack() != null) {
            for (int[] coords : apiOrganism.getLocationStack()) {
                locationStack.add(coords);
            }
        }
        
        // Convert call stack
        List<org.evochora.server.contracts.raw.SerializableProcFrame> callStackList = new ArrayList<>();
        if (apiOrganism.getCallStack() != null) {
            for (org.evochora.datapipeline.api.contracts.SerializableProcFrame apiFrame : apiOrganism.getCallStack()) {
                callStackList.add(convertProcFrame(apiFrame));
            }
        }
        
        // Convert DPS (Data Pointer Stack)
        List<int[]> dps = new ArrayList<>();
        if (apiOrganism.getDp() != null) {
            for (int[] dp : apiOrganism.getDp()) {
                dps.add(dp);
            }
        }
        
        // Convert error state
        boolean instructionFailed = false;
        String failureReason = null;
        if (apiOrganism.getErrorState() != null) {
            instructionFailed = apiOrganism.getErrorState().getReason() != null;
            failureReason = apiOrganism.getErrorState().getReason();
        }
        
        return new org.evochora.server.contracts.raw.RawOrganismState(
            apiOrganism.getOrganismId(),
            apiOrganism.getParentId(),
            apiOrganism.getBirthTick(),
            apiOrganism.getProgramId(),
            apiOrganism.getPosition(), // initialPosition (using current position as fallback)
            apiOrganism.getPosition(), // ip (instruction pointer)
            apiOrganism.getDv(),
            dps,
            apiOrganism.getActiveDp(),
            apiOrganism.getPosition()[0], // er (execution register) - using first coordinate as fallback
            dataRegisters,
            procedureRegisters,
            fpRegisters,
            locationRegisters,
            new java.util.ArrayDeque<>(dataStack),
            new java.util.ArrayDeque<>(locationStack),
            new java.util.ArrayDeque<>(callStackList),
            apiOrganism.isDead(),
            instructionFailed,
            failureReason,
            false, // skipIpAdvance
            apiOrganism.getPosition(), // ipBeforeFetch
            apiOrganism.getDv() // dvBeforeFetch
        );
    }
    
    /**
     * Converts API SerializableProcFrame to server SerializableProcFrame.
     */
    private org.evochora.server.contracts.raw.SerializableProcFrame convertProcFrame(
            org.evochora.datapipeline.api.contracts.SerializableProcFrame apiFrame) {
        // Convert int[] to Object[]
        Object[] savedFprs = new Object[0];
        if (apiFrame.getSavedFormalParamRegisters() != null) {
            savedFprs = new Object[apiFrame.getSavedFormalParamRegisters().length];
            for (int i = 0; i < apiFrame.getSavedFormalParamRegisters().length; i++) {
                savedFprs[i] = apiFrame.getSavedFormalParamRegisters()[i];
            }
        }
        
        return new org.evochora.server.contracts.raw.SerializableProcFrame(
            apiFrame.getProcedureName(),
            apiFrame.getReturnAddress(),
            new Object[0], // savedPrs - empty array as fallback
            savedFprs,
            new java.util.HashMap<>() // fprBindings - empty map as fallback
        );
    }
    
    /**
     * Processes a RawTickState and writes it to the debug database.
     * 
     * @param rawTickState The raw tick state to process
     */
    private void processRawTickState(RawTickState rawTickState) {
        try {
            // Check if we should stop processing
            if (!running.get() || currentState.get() != State.RUNNING) {
                log.debug("Skipping tick {} due to shutdown signal", rawTickState.tickNumber());
                return;
            }
            
            if (environmentProperties == null) {
                log.warn("Skipping tick {} - no environment properties available", rawTickState.tickNumber());
                return;
            }
            
            // Transform raw tick state to prepared tick state
            PreparedTickState preparedTickState = tickProcessor.transformRawToPrepared(
                rawTickState, 
                programArtifacts, 
                environmentProperties
            );
            
            // Serialize and compress the prepared tick state
            byte[] compressedData = compressTickData(preparedTickState);
            
            // Write to database
            databaseManager.writePreparedTick(rawTickState.tickNumber(), compressedData);
            
            // Update last processed tick
            lastProcessedTick = rawTickState.tickNumber();
            
        } catch (Exception e) {
            log.error("Error processing raw tick state for tick {}: {}", rawTickState.tickNumber(), e.getMessage(), e);
        }
    }
    
    /**
     * Compresses tick data using GZIP compression.
     * 
     * @param preparedTickState The prepared tick state to compress
     * @return The compressed data as byte array
     * @throws IOException If compression fails
     */
    private byte[] compressTickData(PreparedTickState preparedTickState) throws IOException {
        // Serialize to JSON
        byte[] jsonData = objectMapper.writeValueAsBytes(preparedTickState);
        
        // Compress with GZIP
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipStream = new GZIPOutputStream(byteStream)) {
            gzipStream.write(jsonData);
        }
        
        return byteStream.toByteArray();
    }
    
    /**
     * Gets the current status information for this service.
     * 
     * @return Service status with processing statistics
     */
    public String getAdditionalInfo() {
        if (!enabled) {
            return "Disabled";
        }
        
        StringBuilder info = new StringBuilder();
        info.append("Artifacts: ").append(programArtifacts.size());
        info.append(", Batches: ").append(databaseManager.getActualBatchCount());
        info.append(", Pending: ").append(databaseManager.getBatchCount());
        
        return info.toString();
    }
    
    /**
     * Parses database configuration from Config object.
     * 
     * @param dbConfig The database configuration section
     * @return Parsed database configuration
     */
    private SimulationConfiguration.DatabaseConfig parseDatabaseConfig(Config dbConfig) {
        SimulationConfiguration.DatabaseConfig config = new SimulationConfiguration.DatabaseConfig();
        
        if (dbConfig.hasPath("cacheSize")) {
            config.cacheSize = dbConfig.getInt("cacheSize");
        }
        
        // Note: synchronous field doesn't exist in DatabaseConfig, so we skip it
        
        return config;
    }
    
    /**
     * Parses memory optimization configuration from Config object.
     * 
     * @param memConfig The memory optimization configuration section
     * @return Parsed memory optimization configuration
     */
    private SimulationConfiguration.MemoryOptimizationConfig parseMemoryOptimizationConfig(Config memConfig) {
        SimulationConfiguration.MemoryOptimizationConfig config = new SimulationConfiguration.MemoryOptimizationConfig();
        
        if (memConfig.hasPath("enabled")) {
            config.enabled = memConfig.getBoolean("enabled");
        }
        
        return config;
    }
    
    /**
     * Converts API ProgramArtifact to compiler ProgramArtifact.
     * 
     * @param apiArtifact The API artifact to convert
     * @return The converted compiler artifact
     */
    private ProgramArtifact convertToCompilerArtifact(org.evochora.datapipeline.api.contracts.ProgramArtifact apiArtifact) {
        // Convert source map
        Map<Integer, org.evochora.compiler.api.SourceInfo> sourceMap = new HashMap<>();
        if (apiArtifact.getSourceMap() != null) {
            for (Map.Entry<Integer, org.evochora.datapipeline.api.contracts.SerializableSourceInfo> entry : apiArtifact.getSourceMap().entrySet()) {
                org.evochora.datapipeline.api.contracts.SerializableSourceInfo serializableInfo = entry.getValue();
                org.evochora.compiler.api.SourceInfo sourceInfo = new org.evochora.compiler.api.SourceInfo(
                    serializableInfo.sourceName(),
                    serializableInfo.line(),
                    serializableInfo.column()
                );
                sourceMap.put(entry.getKey(), sourceInfo);
            }
        }
        
        // Convert initial world objects
        Map<int[], org.evochora.compiler.api.PlacedMolecule> initialWorldObjects = new HashMap<>();
        if (apiArtifact.getInitialWorldObjects() != null) {
            for (Map.Entry<int[], org.evochora.datapipeline.api.contracts.SerializablePlacedMolecule> entry : apiArtifact.getInitialWorldObjects().entrySet()) {
                org.evochora.datapipeline.api.contracts.SerializablePlacedMolecule serializableMolecule = entry.getValue();
                org.evochora.compiler.api.PlacedMolecule molecule = new org.evochora.compiler.api.PlacedMolecule(
                    serializableMolecule.type(),
                    serializableMolecule.value()
                );
                initialWorldObjects.put(entry.getKey(), molecule);
            }
        }
        
        // Create empty maps for missing fields
        Map<String, Integer> emptyStringIntMap = new HashMap<>();
        Map<Integer, int[]> emptyIntIntArrayMap = new HashMap<>();
        Map<String, List<String>> emptyStringListMap = new HashMap<>();
        Map<org.evochora.compiler.api.SourceInfo, org.evochora.compiler.api.TokenInfo> emptySourceTokenMap = new HashMap<>();
        Map<String, Map<Integer, Map<Integer, List<org.evochora.compiler.api.TokenInfo>>>> emptyTokenLookup = new HashMap<>();
        
        return new ProgramArtifact(
            apiArtifact.getProgramId(),
            apiArtifact.getSources(),
            apiArtifact.getMachineCodeLayout(),
            initialWorldObjects,
            sourceMap,
            emptyIntIntArrayMap, // callSiteBindings
            emptyStringIntMap, // relativeCoordToLinearAddress
            emptyIntIntArrayMap, // linearAddressToCoord
            apiArtifact.getLabelAddressToName(),
            emptyStringIntMap, // registerAliasMap
            emptyStringListMap, // procNameToParamNames
            emptySourceTokenMap, // tokenMap
            emptyTokenLookup // tokenLookup
        );
    }
}
