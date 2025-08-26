package org.evochora.server.indexer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.isa.Instruction;
import org.evochora.server.IControllable;
import org.evochora.server.contracts.debug.PreparedTickState;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.contracts.raw.RawTickState;
import org.evochora.server.contracts.raw.SerializableProcFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.JsonNode;
import org.evochora.compiler.internal.LinearizedProgramArtifact;
import org.evochora.runtime.services.Disassembler;
import org.evochora.runtime.services.DisassemblyData;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.isa.InstructionArgumentType;

public class DebugIndexer implements IControllable, Runnable {

    private static final Logger log = LoggerFactory.getLogger(DebugIndexer.class);

    /**
     * Repräsentiert die Gültigkeit eines ProgramArtifacts für einen Organismus.
     */
    public enum ArtifactValidity {
        /** Kein ProgramArtifact verfügbar */
        NONE,
        /** ProgramArtifact ist vollständig gültig */
        VALID,
        /** Nur Source-Code und Aliase sind sicher, Source-Mapping ist ungültig */
        PARTIAL_SOURCE,
        /** ProgramArtifact ist komplett ungültig */
        INVALID
    }

    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean autoPaused = new AtomicBoolean(false);
    private long startTime = System.currentTimeMillis();
    private long nextTickToProcess = 0L; // Start at 0 to include tick 0
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String rawDbPath;
    private String debugDbPath;
    private final SourceAnnotator sourceAnnotator = new SourceAnnotator();
    private final int batchSize; // Configurable batch size

    private Map<String, ProgramArtifact> artifacts = new HashMap<>();
    private int[] worldShape = new int[]{0, 0};
    
    // Cache für Artifact-Validität pro Organismus (programId_organismId -> ArtifactValidity)
    private final Map<String, ArtifactValidity> validityCache = new HashMap<>();
    
    // Database connection for batch operations
    private Connection connection;
    private PreparedStatement tickInsertStatement;
    private int batchCount = 0;

    public DebugIndexer(String rawDbPath, int batchSize) {
        this(rawDbPath, rawDbPath.replace("_raw.sqlite", "_debug.sqlite"), batchSize);
    }

    public DebugIndexer(String rawDbUrl, String debugDbUrl, int batchSize) {
        this.rawDbPath = rawDbUrl;
        this.debugDbPath = debugDbUrl;
        this.batchSize = batchSize;
        this.thread = new Thread(this, "DebugIndexer");
        this.thread.setDaemon(true);
    }

    public static Optional<DebugIndexer> createForLatest() throws IOException {
        Path runsDir = Paths.get(Config.RUNS_DIRECTORY);
        if (!Files.exists(runsDir)) {
            return Optional.empty();
        }
        Optional<Path> latestRawDb = Files.list(runsDir)
                .filter(p -> p.getFileName().toString().endsWith("_raw.sqlite"))
                .max(Comparator.comparingLong(p -> p.toFile().lastModified()));

        return latestRawDb.map(path -> new DebugIndexer(path.toAbsolutePath().toString(), 1000)); // Default batch size
    }
    
    /**
     * Updates the raw database path to match the current persistence service.
     * This is needed when the simulation is restarted and a new database is created.
     */
    public void updateRawDbPath(String newRawDbPath) {
        // Close current database connection
        try {
            if (tickInsertStatement != null) {
                tickInsertStatement.close();
                tickInsertStatement = null;
            }
            if (connection != null) {
                connection.close();
                connection = null;
            }
                 } catch (Exception e) {
             // Ignore errors when closing resources
         }
        
        // Update paths
        this.rawDbPath = newRawDbPath;
        this.debugDbPath = newRawDbPath.replace("_raw.sqlite", "_debug.sqlite");
        
        // Reset counters
        this.nextTickToProcess = 0L;
        this.batchCount = 0;
        
        
    }
    
    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            String rawDbName = rawDbPath.substring(rawDbPath.lastIndexOf('/') + 1);
            String debugDbName = debugDbPath.substring(debugDbPath.lastIndexOf('/') + 1);
            log.info("DebugIndexer: reading {} writing {}", rawDbName, debugDbName);
            setupDebugDatabase();
            thread.start();
        }
    }

    @Override
    public void pause() {
        // Manual pause always sets autoPaused to false
        // Auto-pause will set autoPaused to true before calling pause()
        autoPaused.set(false);
        
        // Set the pause flag - the service will pause after finishing current batch
        paused.set(true);
        log.debug("Manual pause flag set, service will pause after finishing current batch and close database");
        
        // For manual pause: immediately close database to ensure WAL is properly flushed
        // This prevents WAL files from remaining open
        if (Thread.currentThread().getName().equals("DebugIndexer")) {
            // Called from service thread - close immediately
            log.debug("Manual pause from service thread - closing database immediately");
            closeQuietly();
        } else {
            // Called from external thread - signal immediate close
            log.debug("Manual pause from external thread - signaling immediate database close");
        }
    }

    @Override
    public void resume() {
        paused.set(false);
        autoPaused.set(false);
        
        // Reopen database connection if it was closed during manual pause
        try {
            if (connection == null || connection.isClosed()) {
                log.debug("Resuming from manual pause - reopening database connection");
                setupDebugDatabase();
                batchCount = 0;
            }
        } catch (Exception e) {
            log.warn("Failed to reopen database connection on resume: {}", e.getMessage());
        }
    }

    @Override
    public void shutdown() {
        if (running.compareAndSet(true, false)) {
            long currentTick = getLastProcessedTick();
            double tps = calculateTPS();
            // Only log if called from the service thread
            if (Thread.currentThread().getName().equals("DebugIndexer")) {
                log.info("DebugIndexer: graceful termination tick:{} TPS:{}", currentTick, String.format("%.2f", tps));
            }
            thread.interrupt();
        }
    }
    
    public void forceShutdown() {
        if (running.get()) {
        running.set(false);
        thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isPaused() {
        return paused.get();
    }
    
    @Override
    public boolean isAutoPaused() {
        return autoPaused.get();
    }
    


    private double calculateTPS() {
        long currentTime = System.currentTimeMillis();
        long currentTick = nextTickToProcess; // Use local counter instead of database query
        
        if (currentTick <= 0) {
            return 0.0;
        }
        
        // Calculate TPS since tick 0
        long timeDiff = currentTime - startTime;
        if (timeDiff > 0) {
            return (double) currentTick / (timeDiff / 1000.0);
        }
        
        return 0.0;
    }

    public String getStatus() {
        if (!running.get()) {
            return "stopped";
        }
        
        if (paused.get()) {
            try {
                long currentTick = getLastProcessedTick(); // Use last processed tick instead of next tick to process
                String rawDbInfo = rawDbPath != null ? rawDbPath.replace('/', '\\') : "unknown";
                String debugDbInfo = debugDbPath != null ? debugDbPath.replace('/', '\\') : "unknown";
                String status = autoPaused.get() ? "auto-paused" : "paused";
                return String.format("%s tick:%d reading %s writing %s", 
                        status, currentTick, rawDbInfo, debugDbInfo);
            } catch (Exception e) {
                return String.format("paused ERROR:%s", e.getMessage());
            }
        }
        
        try {
            long currentTick = getLastProcessedTick(); // Use last processed tick instead of next tick to process
            String rawDbInfo = rawDbPath != null ? rawDbPath.replace('/', '\\') : "unknown";
            String debugDbInfo = debugDbPath != null ? debugDbPath.replace('/', '\\') : "unknown";
            double tps = calculateTPS();
            
            return String.format("started tick:%d reading %s writing %s TPS:%.2f", 
                    currentTick, rawDbInfo, debugDbInfo, tps);
        } catch (Exception e) {
            return String.format("started ERROR:%s", e.getMessage());
        }
    }
    
    private void resetTPS() {
        // lastStatusTime = 0; // Removed
        // lastStatusTick = 0; // Removed
        // lastTPS = 0.0; // Removed
    }

    private Connection createConnection(String pathOrUrl) throws Exception {
        if (pathOrUrl.startsWith("jdbc:")) {
            return DriverManager.getConnection(pathOrUrl);
        }
        // For file paths, check if this is a test environment and convert to in-memory
        if (pathOrUrl.contains("memdb_") || pathOrUrl.contains("test_")) {
            // This is a test database, use in-memory mode
            return DriverManager.getConnection("jdbc:sqlite:file:" + pathOrUrl + "?mode=memory&cache=shared");
        }
        // For production file paths, use regular SQLite
        return DriverManager.getConnection("jdbc:sqlite:" + pathOrUrl);
    }

    private void setupDebugDatabase() {
        try {
            connection = createConnection(debugDbPath);
            
            // Add performance hints for SQLite
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL"); // Write-Ahead Logging for better concurrency
                st.execute("PRAGMA synchronous=NORMAL"); // Faster writes
                st.execute("PRAGMA cache_size=10000"); // Larger cache
                st.execute("PRAGMA temp_store=MEMORY"); // Use memory for temp tables
                
                // Create tables
                st.execute("CREATE TABLE IF NOT EXISTS prepared_ticks (tick_number INTEGER PRIMARY KEY, tick_data_json TEXT)");
                st.execute("CREATE TABLE IF NOT EXISTS simulation_metadata (key TEXT PRIMARY KEY, value TEXT)");
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to setup debug database", e);
        }
    }

    @Override
    public void run() {
        try {
            loadInitialData();
            // Start processing from tick 0 (nextTickToProcess = 0L from initialization)

            while (running.get()) {
                if (paused.get()) {
                    // In pause mode: check periodically for new ticks (only if auto-paused)
                    if (autoPaused.get()) {
                        try {
                            Thread.sleep(1000); // Check every second for new ticks
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        
                        // Check if there are new ticks to process (only if auto-paused)
                        if (hasNewTicksToProcess()) {
                            log.debug("New ticks detected, waking up from auto-pause");
                            autoPaused.set(false);
                            paused.set(false);
                            continue;
                        }
                    } else {
                        // Manually paused - ensure database is closed and just wait
                        if (connection != null && !connection.isClosed()) {
                            log.debug("Manual pause detected - ensuring database is closed");
                            closeQuietly();
                        }
                        try {
                            Thread.sleep(1000); // Check every second
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    continue;
                }
                
                // Check if we need to reopen database after manual pause
                if (connection == null || connection.isClosed()) {
                    log.debug("Reopening database connection after manual pause");
                    setupDebugDatabase();
                    batchCount = 0;
                }

                try {
                    int remainingTicks = processNextBatch();

                    if (remainingTicks > 0) {
                        // Continue processing immediately if we processed ticks
                        // No sleep - maximum throughput!
                        continue;
                    } else if (remainingTicks == 0) {
                        // remainingTicks == 0 means no more ticks are currently available
                        // But we need to check if new ticks arrived while we were processing
                        // If new ticks arrived, continue immediately; otherwise auto-pause
                        if (hasNewTicksToProcess()) {
                            // New ticks arrived while we were processing - continue immediately
                            log.debug("New ticks available after processing batch, continuing immediately");
                            continue;
                        } else {
                            // No more ticks available - auto-pause to save resources
                            log.debug("No more ticks to process, auto-pausing indexer");
                            
                            // For auto-pause: execute any remaining incomplete batches before pausing
                            // This ensures all data is committed and available to other threads
                            if (tickInsertStatement != null && batchCount > 0) {
                                try {
                                    int[] result = tickInsertStatement.executeBatch();
                                    log.debug("Executed final batch of {} ticks before auto-pause, result: {}", batchCount, java.util.Arrays.toString(result));
                                    tickInsertStatement.clearBatch();
                                    batchCount = 0; // Reset batch counter after execution
                                } catch (Exception e) {
                                    log.warn("Error executing final batch before auto-pause: {}", e.getMessage());
                                }
                            }
                            
                            // Auto-pause - mark as auto-paused and set pause flag
                            autoPaused.set(true);
                            paused.set(true);
                            
                            // For auto-pause: keep database open for quick resume
                            log.debug("Auto-pause: keeping database open for quick resume");
                            
                            // Wait a bit before checking for new ticks
                            try {
                                Thread.sleep(1000); // Check every second
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                            
                            // Check if new ticks are available (only if not manually paused)
                            if (hasNewTicksToProcess() && !paused.get()) {
                                log.debug("New ticks available, resuming debug indexer");
                                autoPaused.set(false);
                                paused.set(false);
                                continue;
                            }
                            
                            continue;
                        }
                    } else {
                        // remainingTicks < 0 means there was an error, but ticks might still be available
                        // Check if there are truly no more ticks to process
                        if (hasNewTicksToProcess()) {
                            // There are still ticks, but they might be in a different batch
                            // Continue processing without pause
                            continue;
                        } else {
                            // No more ticks available - auto-pause to save resources
                            log.debug("No more ticks to process, auto-pausing indexer");
                            
                            // For auto-pause: execute any remaining incomplete batches before pausing
                            // This ensures all data is committed and available to other threads
                            if (tickInsertStatement != null && batchCount > 0) {
                                try {
                                    int[] result = tickInsertStatement.executeBatch();
                                    log.debug("Executed final batch of {} ticks before auto-pause, result: {}", batchCount, java.util.Arrays.toString(result));
                                    tickInsertStatement.clearBatch();
                                    batchCount = 0; // Reset batch counter after execution
                                } catch (Exception e) {
                                    log.warn("Error executing final batch before auto-pause: {}", e.getMessage());
                                }
                            }
                            
                            // Auto-pause - mark as auto-paused and set pause flag
                            autoPaused.set(true);
                            paused.set(true);
                            
                            // For auto-pause: keep database open for quick resume
                            log.debug("Auto-pause: keeping database open for quick resume");
                            
                            // Wait a bit before checking for new ticks
                            try {
                                Thread.sleep(1000); // Check every second
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                            
                            // Check if new ticks are available (only if not manually paused)
                            if (hasNewTicksToProcess() && !paused.get()) {
                                log.debug("New ticks available, resuming debug indexer");
                                autoPaused.set(false);
                                paused.set(false);
                                continue;
                            }
                            
                            continue;
                        }
                    }
                } catch (Exception e) {
                    if (running.get()) { // Only warn if still running
                        log.warn("Failed to process tick {}: {}", nextTickToProcess, e.getMessage());
                    }
                }
                
                // Manual pause is now handled immediately in the pause() method
                // and in the main pause loop above
            }
        } catch (Exception e) {
            log.error("DebugIndexer fatal error, terminating service: {}", e.getMessage());
        } finally {
            // Log graceful termination from the service thread
            if (Thread.currentThread().getName().equals("DebugIndexer")) {
                long currentTick = getLastProcessedTick(); // Use getter method
                double tps = calculateTPS();
                log.info("DebugIndexer: graceful termination tick:{} TPS:{}", currentTick, String.format("%.2f", tps));
            }
            
            // Cleanup database resources
            try {
                if (tickInsertStatement != null) {
                    if (batchCount > 0) {
                        tickInsertStatement.executeBatch();
                        log.debug("Executed final batch of {} ticks during shutdown", batchCount);
                    }
                    tickInsertStatement.close();
                }
                if (connection != null) {
                    connection.close();
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * Checks if there are new ticks available to process.
     * Used to wake up from pause mode when new ticks arrive.
     */
    private boolean hasNewTicksToProcess() {
        try (Connection rawConn = createConnection(rawDbPath);
             PreparedStatement countPs = rawConn.prepareStatement(
                     "SELECT COUNT(*) FROM raw_ticks WHERE tick_number >= ?")) {
            countPs.setLong(1, nextTickToProcess);
            try (ResultSet countRs = countPs.executeQuery()) {
                if (countRs.next()) {
                    return countRs.getLong(1) > 0;
                }
            }
                 } catch (Exception e) {
             // Ignore errors when checking for new ticks
         }
        return false;
    }

    private void loadInitialData() {
        // Wait for raw database to be available
        while (running.get()) {
        try (Connection rawConn = createConnection(rawDbPath);
             Statement st = rawConn.createStatement()) {
                
                // Check if tables exist
                try (ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='program_artifacts'")) {
                    if (!rs.next()) {
                        // Tables don't exist yet, wait and retry
                        log.warn("Waiting for raw database tables to be created...");
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        continue;
                    }
                }
                
                // Load program artifacts
            try (ResultSet rs = st.executeQuery("SELECT program_id, artifact_json FROM program_artifacts")) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    String json = rs.getString(2);
                    
                    // Deserialisierung zu LinearizedProgramArtifact
                    LinearizedProgramArtifact linearized = objectMapper.readValue(json, LinearizedProgramArtifact.class);
                    
                    // Konvertierung zurück zu ProgramArtifact
                    ProgramArtifact artifact = linearized.toProgramArtifact();
                    this.artifacts.put(id, artifact);
                }
            }
                
                // Load world shape
            try (ResultSet rs = st.executeQuery("SELECT key, value FROM simulation_metadata WHERE key = 'worldShape'")) {
                if (rs.next()) {
                    this.worldShape = objectMapper.readValue(rs.getString(2), int[].class);
                }
            }
                
                log.info("Successfully loaded initial data from raw database");
                break; // Successfully loaded, exit the loop
                
        } catch (Exception e) {
                if (running.get()) {
                    log.info("Waiting for raw database to be available: {}", e.getMessage());
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    break; // Service is shutting down
                }
            }
        }
    }

    /**
     * Process next batch of ticks, up to the batch size limit.
     * If fewer ticks than batch size are available, process them anyway.
     * @return Number of ticks still remaining to be processed, or -1 for error
     */
    private int processNextBatch() {
        try (Connection rawConn = createConnection(rawDbPath)) {
            // Apply SQLite performance optimizations
            try (Statement stmt = rawConn.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
                stmt.execute("PRAGMA cache_size=10000");
                stmt.execute("PRAGMA temp_store=MEMORY");
            }
            
            // Count available ticks starting from the next one to process
            long totalAvailableTicks = 0;
            try (PreparedStatement countPs = rawConn.prepareStatement(
                    "SELECT COUNT(*) FROM raw_ticks WHERE tick_number >= ?")) {
                countPs.setLong(1, nextTickToProcess);
                try (ResultSet countRs = countPs.executeQuery()) {
                    if (countRs.next()) {
                        totalAvailableTicks = countRs.getLong(1);
                    }
                }
            } catch (Exception e) {
                log.warn("COUNT query failed: {}", e.getMessage());
                return -1; // Indicate error
            }
            
            // If we have no ticks remaining, return 0
            if (totalAvailableTicks == 0) {
                return 0;
            }
            
            // Process up to batch size ticks, but process fewer if that's all that's available
            // This ensures we don't wait for a full batch - we process whatever is available
            int actualBatchSize = Math.min((int)totalAvailableTicks, batchSize);
            
            log.debug("Processing {} ticks (available: {}, batch limit: {})", 
                     actualBatchSize, totalAvailableTicks, batchSize);
            
            try (PreparedStatement ps = rawConn.prepareStatement(
                    "SELECT tick_number, tick_data_json FROM raw_ticks WHERE tick_number >= ? ORDER BY tick_number LIMIT ?")) {
                ps.setLong(1, nextTickToProcess);
                ps.setInt(2, actualBatchSize);
                
                try (ResultSet rs = ps.executeQuery()) {
                    boolean processedAny = false;
                    int processedCount = 0;
                    
                    while (rs.next()) {
                        long tickNumber = rs.getLong(1);
                        String rawTickJson = rs.getString(2);
                        
                        try {
                            RawTickState rawTickState = objectMapper.readValue(rawTickJson, new TypeReference<>() {});
                            PreparedTickState preparedTick = transformRawToPrepared(rawTickState);
                            writePreparedTick(preparedTick);
                            
                            // Update next tick to process
                            if (tickNumber >= nextTickToProcess) {
                                nextTickToProcess = tickNumber + 1;
                            }
                            processedAny = true;
                            processedCount++;
                        } catch (Exception e) {
                            if (running.get()) {
                                log.warn("Failed to process tick {}: {}", tickNumber, e.getMessage());
                            }
                        }
                    }
                    
                    if (processedAny) {
                        log.debug("Processed {} ticks in batch, next tick to process: {}", processedCount, nextTickToProcess);
                    }
                    
                    // Calculate remaining ticks after processing
                    long remainingAfterProcessing = totalAvailableTicks - processedCount;
                    
                    // Return the number of ticks that are still remaining to be processed
                    return (int)remainingAfterProcessing;
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                log.warn("Failed to process next batch: {}", e.getMessage());
            }
            return -1; // Indicate error
        }
    }

    private PreparedTickState transformRawToPrepared(RawTickState raw) {
        PreparedTickState.WorldMeta meta = new PreparedTickState.WorldMeta(this.worldShape);

        List<PreparedTickState.Cell> cells = raw.cells().stream()
                .map(c -> {
                    Molecule m = Molecule.fromInt(c.molecule());
                    String opcodeName = null;
                    if (m.type() == Config.TYPE_CODE && m.value() != 0) {
                        opcodeName = Instruction.getInstructionNameById(m.toInt());
                    }
                    return new PreparedTickState.Cell(toList(c.pos()), typeIdToName(m.type()), m.toScalarValue(), c.ownerId(), opcodeName);
                }).toList();

        List<PreparedTickState.OrganismBasic> orgBasics = raw.organisms().stream()
                .filter(o -> !o.isDead())
                .map(o -> new PreparedTickState.OrganismBasic(o.id(), o.programId(), toList(o.ip()), o.er(), o.dps().stream().map(this::toList).toList(), toList(o.dv())))
                .toList();

        PreparedTickState.WorldState worldState = new PreparedTickState.WorldState(cells, orgBasics);

        Map<String, PreparedTickState.OrganismDetails> details = new HashMap<>();
        for (RawOrganismState o : raw.organisms()) {
            if (o.isDead()) continue;
            
            // Zentrale Methode für alle Organismus-Details
            PreparedTickState.OrganismDetails organismDetails = buildOrganismDetails(o, raw);
            details.put(String.valueOf(o.id()), organismDetails);
        }

        return new PreparedTickState("debug", raw.tickNumber(), meta, worldState, details);
    }

    /**
     * Zentrale Methode zum Erstellen aller Organismus-Details.
     * Koordiniert die Validierung und ruft alle Builder auf.
     */
    private PreparedTickState.OrganismDetails buildOrganismDetails(RawOrganismState o, RawTickState rawTickState) {
        ProgramArtifact artifact = this.artifacts.get(o.programId());
        ArtifactValidity validity = checkArtifactValidity(o, artifact);
        
        var basicInfo = new PreparedTickState.BasicInfo(o.id(), o.programId(), o.parentId(), o.birthTick(), o.er(), toList(o.ip()), toList(o.dv()));
        var nextInstruction = buildNextInstruction(o, artifact, validity, rawTickState);
        var internalState = buildInternalState(o, artifact, validity);
        var sourceView = buildSourceView(o, artifact, validity);

        return new PreparedTickState.OrganismDetails(basicInfo, nextInstruction, internalState, sourceView);
    }

    /**
     * Prüft die Gültigkeit eines ProgramArtifacts für einen Organismus.
     * Verwendet Caching für Performance-Optimierung.
     */
    private ArtifactValidity checkArtifactValidity(RawOrganismState o, ProgramArtifact artifact) {
        if (artifact == null) {
            return ArtifactValidity.NONE;
        }
        
        // Cache-Key: programId_organismId
        String cacheKey = o.programId() + "_" + o.id();
        
        // Cache-Check
        ArtifactValidity cached = validityCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        
        // Neue Validierung
        ArtifactValidity validity = performValidation(o, artifact);
        validityCache.put(cacheKey, validity);
        
        return validity;
    }

    /**
     * Führt die eigentliche Validierung durch.
     * Hybrid-Ansatz: Schnell-Check (IP im SourceMap) + detaillierter Check (Code-Konsistenz).
     */
    private ArtifactValidity performValidation(RawOrganismState o, ProgramArtifact artifact) {
        // Schnell-Check: IP im SourceMap?
        boolean ipValid = isIpInSourceMap(o, artifact);
        if (!ipValid) {
            return ArtifactValidity.INVALID;
        }
        
        // Detaillierter Check: Maschinencode-Konsistenz
        CodeConsistency consistency = checkCodeConsistency(o, artifact);
        
        if (consistency.isFullyConsistent()) {
            return ArtifactValidity.VALID;
        } else if (consistency.isPartiallyConsistent()) {
            return ArtifactValidity.PARTIAL_SOURCE;
        } else {
            return ArtifactValidity.INVALID;
        }
    }

    /**
     * Schnell-Check: Ist die aktuelle IP-Position im SourceMap des Artifacts?
     */
    private boolean isIpInSourceMap(RawOrganismState o, ProgramArtifact artifact) {
        if (artifact.sourceMap() == null || artifact.relativeCoordToLinearAddress() == null) {
            return false;
        }
        
        // Berechne relative IP-Position
        int[] origin = o.initialPosition();
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < o.ip().length; i++) {
            if (i > 0) key.append('|');
            key.append(o.ip()[i] - origin[i]);
        }
        
        // Prüfe, ob die aktuelle IP im sourceMap existiert
        Integer addr = artifact.relativeCoordToLinearAddress().get(key.toString());
        return addr != null && artifact.sourceMap().containsKey(addr);
    }

    /**
     * Detaillierter Check: Maschinencode-Konsistenz um die aktuelle IP.
     */
    private CodeConsistency checkCodeConsistency(RawOrganismState o, ProgramArtifact artifact) {
        if (artifact.machineCodeLayout() == null) {
            return new CodeConsistency(0, 0, false);
        }
        
        // Prüfe Maschinencode um die aktuelle IP
        int[] currentIp = o.ip();
        int[] dv = o.dv();
        
        int matchingPositions = 0;
        int totalPositions = 0;
        int[] checkPos = currentIp.clone();
        
        // Prüfe 5 Instruktionen um die aktuelle IP
        for (int i = 0; i < 5; i++) {
            String posKey = Arrays.toString(checkPos);
            if (artifact.machineCodeLayout().containsKey(posKey)) {
                matchingPositions++;
            }
            totalPositions++;
            
            // Gehe zur nächsten Instruktion
            checkPos = getNextPosition(checkPos, dv);
        }
        
        double consistencyRatio = (double) matchingPositions / totalPositions;
        
        if (consistencyRatio >= 0.8) {
            return new CodeConsistency(matchingPositions, totalPositions, true);
        } else if (consistencyRatio >= 0.3) {
            return new CodeConsistency(matchingPositions, totalPositions, false);
        } else {
            return new CodeConsistency(matchingPositions, totalPositions, false);
        }
    }

    /**
     * Berechnet die nächste Position basierend auf dem Direction Vector.
     */
    private int[] getNextPosition(int[] currentPos, int[] dv) {
        int[] next = new int[currentPos.length];
        for (int i = 0; i < currentPos.length; i++) {
            next[i] = currentPos[i] + dv[i];
        }
        return next;
    }

    /**
     * Repräsentiert die Konsistenz des Maschinencodes.
     */
    private static class CodeConsistency {
        private final int matchingPositions;
        private final int totalPositions;
        private final boolean isFullyConsistent;
        
        public CodeConsistency(int matchingPositions, int totalPositions, boolean isFullyConsistent) {
            this.matchingPositions = matchingPositions;
            this.totalPositions = totalPositions;
            this.isFullyConsistent = isFullyConsistent;
        }
        
        public boolean isFullyConsistent() {
            return isFullyConsistent;
        }
        
        public boolean isPartiallyConsistent() {
            return !isFullyConsistent && (double) matchingPositions / totalPositions >= 0.3;
        }
    }

    private PreparedTickState.NextInstruction buildNextInstruction(RawOrganismState o, ProgramArtifact artifact, ArtifactValidity validity, RawTickState rawTickState) {
        try {
            // Erstelle RawTickStateReader für diesen Organismus
            EnvironmentProperties props = new EnvironmentProperties(this.worldShape, true); // isToroidal = true
            RawTickStateReader reader = new RawTickStateReader(rawTickState, props);
            
            // Verwende den neuen Disassembler
            Disassembler disassembler = new Disassembler();
            DisassemblyData data = disassembler.disassemble(reader, o.ip());
            
            if (data == null) {
                return new PreparedTickState.NextInstruction(
                    "Disassembly failed", null, null, "ERROR"
                );
            }
            
            // Bestimme den runtimeStatus basierend auf der Validität
            String runtimeStatus = determineRuntimeStatus(validity);
            
            // Erstelle die NextInstruction
            return new PreparedTickState.NextInstruction(
                formatDisassembly(data),
                null, // sourceFile - wird nicht gesetzt bei Runtime-only Disassembly
                null, // sourceLine - wird nicht gesetzt bei Runtime-only Disassembly
                runtimeStatus
            );
            
        } catch (Exception e) {
            log.warn("Failed to disassemble instruction for organism {}: {}", o.id(), e.getMessage());
            return new PreparedTickState.NextInstruction(
                "Disassembly failed: " + e.getMessage(),
                null,
                null,
                "ERROR"
            );
        }
    }
    
    /**
     * Bestimmt den runtimeStatus basierend auf der Artifact-Validität.
     */
    private String determineRuntimeStatus(ArtifactValidity validity) {
        switch (validity) {
            case NONE:
                return "CODE_UNAVAILABLE";
            case VALID:
                return "OK";
            case PARTIAL_SOURCE:
                return "COMPILER_GENERATED";
            case INVALID:
                return "CODE_UNAVAILABLE";
            default:
                return "UNKNOWN";
        }
    }
    
    /**
     * Formatiert die Disassembly-Informationen in einen lesbaren String.
     */
    private String formatDisassembly(DisassemblyData data) {
        StringBuilder sb = new StringBuilder();
        sb.append(data.opcodeName());
        
        if (data.argValues().length > 0) {
            sb.append(" ");
            for (int i = 0; i < data.argValues().length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(formatArgument(data.argValues()[i]));
            }
        }
        
        return sb.toString();
    }
    
    /**
     * Formatiert ein einzelnes Argument für die Anzeige.
     */
    private String formatArgument(int argValue) {
        // Vereinfachte Formatierung - wir haben nur den Wert
        return String.valueOf(argValue);
    }

    private PreparedTickState.InternalState buildInternalState(RawOrganismState o, ProgramArtifact artifact, ArtifactValidity validity) {
        // Data Registers (DR) - dynamische Anzahl
        List<PreparedTickState.RegisterValue> dataRegisters = buildRegisterValues(o.drs(), "DR");
        
        // Procedure Registers (PR) - dynamische Anzahl
        List<PreparedTickState.RegisterValue> procRegisters = buildRegisterValues(o.prs(), "PR");
        
        // Floating Point Registers (FPR) - dynamische Anzahl
        List<PreparedTickState.RegisterValue> fpRegisters = buildRegisterValues(o.fprs(), "FPR");
        
        // Location Registers (LR) - dynamische Anzahl
        List<PreparedTickState.RegisterValue> locationRegisters = buildRegisterValues(o.lrs(), "LR");
        
        // Data Stack (DS) - als String-Liste
        List<String> dataStack = o.dataStack() != null ? 
            o.dataStack().stream().map(this::formatValue).toList() : new ArrayList<>();
        
        // Location Stack (LS) - als String-Liste
        List<String> locationStack = o.locationStack() != null ? 
            o.locationStack().stream().map(this::formatVector).toList() : new ArrayList<>();
        
        // Call Stack (CS) - als strukturierte Daten
        List<PreparedTickState.CallStackEntry> callStack = buildCallStack(o, artifact);
        
        // DPS aus dem Organismus extrahieren
        List<List<Integer>> dps = o.dps() != null ? o.dps().stream().map(this::toList).toList() : new ArrayList<>();
        
        return new PreparedTickState.InternalState(
            dataRegisters,      // dataRegisters
            procRegisters,      // procRegisters  
            fpRegisters,        // fpRegisters
            locationRegisters,  // locationRegisters
            dataStack,          // dataStack
            locationStack,      // locationStack
            callStack,          // callStack
            dps                 // dps
        );
    }

    private PreparedTickState.SourceView buildSourceView(RawOrganismState o, ProgramArtifact artifact, ArtifactValidity validity) {
        // Rudimentäre Implementierung - gibt leere Strukturen zurück
        if (artifact == null || validity == ArtifactValidity.INVALID) {
            return new PreparedTickState.SourceView(
                null,           // fileName
                null,           // currentLine
                new ArrayList<>(), // lines
                new ArrayList<>()  // inlineSpans
            );
        }
        
        // Wenn wir ein gültiges Artifact haben, geben wir minimale Informationen zurück
        return new PreparedTickState.SourceView(
            "unknown.s",       // fileName - placeholder
            1,                 // currentLine - placeholder
            new ArrayList<>(), // lines - leer für jetzt
            new ArrayList<>()  // inlineSpans - leer für jetzt
        );
    }

    private List<PreparedTickState.CallStackEntry> buildCallStack(RawOrganismState o, ProgramArtifact artifact) {
        if (o.callStack() == null || o.callStack().isEmpty()) return Collections.emptyList();

        return o.callStack().stream()
                .map(frame -> buildCallStackEntry(frame, o, artifact))
                .collect(Collectors.toList());
    }

    private PreparedTickState.CallStackEntry buildCallStackEntry(SerializableProcFrame frame, RawOrganismState o, ProgramArtifact artifact) {
        // 1. Prozedurname
        String procName = frame.procName();
        
        // 2. Absolute Return-IP als Koordinaten
        int[] returnCoordinates = frame.absoluteReturnIp() != null && frame.absoluteReturnIp().length >= 2 ? 
            frame.absoluteReturnIp() : new int[]{0, 0};
        
        // 3. Parameter-Bindungen
        List<PreparedTickState.ParameterBinding> parameters = new ArrayList<>();
        
        if (frame.fprBindings() != null && !frame.fprBindings().isEmpty()) {
            // Sortiere FPR-Bindings nach Index für konsistente Anzeige
            List<Map.Entry<Integer, Integer>> sortedBindings = frame.fprBindings().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .collect(Collectors.toList());
            
            for (Map.Entry<Integer, Integer> binding : sortedBindings) {
                int fprIndex = binding.getKey() - Instruction.FPR_BASE; // FPR-Base abziehen
                int drId = binding.getValue();
                
                // Hole aktuellen Register-Wert
                String registerValue = "";
                if (drId >= 0 && drId < o.drs().size()) {
                    Object drValue = o.drs().get(drId);
                    if (drValue != null) {
                        registerValue = formatValue(drValue);
                    }
                }
                
                // Parameter-Name auflösen (falls verfügbar)
                String paramName = null;
                if (artifact != null && artifact.procNameToParamNames().containsKey(frame.procName().toUpperCase())) {
                    List<String> paramNames = artifact.procNameToParamNames().get(frame.procName().toUpperCase());
                    if (fprIndex < paramNames.size()) {
                        paramName = paramNames.get(fprIndex);
                    }
                }
                
                // ParameterBinding erstellen
                parameters.add(new PreparedTickState.ParameterBinding(drId, registerValue, paramName));
            }
        }
        
        return new PreparedTickState.CallStackEntry(procName, returnCoordinates, parameters, frame.fprBindings());
    }

    private String formatValue(Object obj) {
        if (obj instanceof Integer i) {
            Molecule m = Molecule.fromInt(i);
            return String.format("%s:%d", typeIdToName(m.type()), m.toScalarValue());
        } else if (obj instanceof int[] v) {
            return formatVector(v);
        }
        return "null";
    }

    private String formatVector(int[] vector) {
        if (vector == null) return "[]";
        return "[" + Arrays.stream(vector).mapToObj(String::valueOf).collect(Collectors.joining("|")) + "]";
    }

    private String typeIdToName(int typeId) {
        if (typeId == Config.TYPE_CODE) return "CODE";
        if (typeId == Config.TYPE_DATA) return "DATA";
        if (typeId == Config.TYPE_ENERGY) return "ENERGY";
        if (typeId == Config.TYPE_STRUCTURE) return "STRUCTURE";
        return "UNKNOWN";
    }

    private List<Integer> toList(int[] arr) {
        if (arr == null) return Collections.emptyList();
        return Arrays.stream(arr).boxed().collect(Collectors.toList());
    }
    
    /**
     * Erstellt eine Liste von RegisterValue-Objekten für die tatsächlich vorhandenen Register.
     * Arbeitet dynamisch mit der Anzahl der verfügbaren Register.
     */
    private List<PreparedTickState.RegisterValue> buildRegisterValues(List<Object> rawRegisters, String prefix) {
        List<PreparedTickState.RegisterValue> result = new ArrayList<>();
        
        if (rawRegisters == null || rawRegisters.isEmpty()) {
            return result; // Leere Liste zurückgeben
        }
        
        for (int i = 0; i < rawRegisters.size(); i++) {
            String registerId = prefix + i;
            String alias = ""; // Keine Aliase für jetzt
            String value = "";
            
            Object rawValue = rawRegisters.get(i);
            if (rawValue != null) {
                value = formatValue(rawValue);
            }
            
            result.add(new PreparedTickState.RegisterValue(registerId, alias, value));
        }
        
        return result;
    }

    private void writePreparedTick(PreparedTickState preparedTick) throws Exception {
                 // Ensure database connection is available
         if (connection == null || connection.isClosed()) {
             setupDebugDatabase();
         }
        
        // Use batch insert for better performance
        if (tickInsertStatement == null) {
            tickInsertStatement = connection.prepareStatement(
                "INSERT OR REPLACE INTO prepared_ticks(tick_number, tick_data_json) VALUES (?, ?)");
        }
        
        String json = objectMapper.writeValueAsString(preparedTick);
        tickInsertStatement.setLong(1, preparedTick.tickNumber());
        tickInsertStatement.setString(2, json);
        tickInsertStatement.addBatch();
        
        // Execute batch every 1000 ticks for optimal performance
        if (++batchCount % 1000 == 0) {
            tickInsertStatement.executeBatch();
            tickInsertStatement.clearBatch();
        }
    }

    public long getLastProcessedTick() { 
        return nextTickToProcess > 0 ? nextTickToProcess - 1 : 0; 
    }
    public String getRawDbPath() { return rawDbPath; }
    public String getDebugDbPath() { return debugDbPath; }

    private void closeQuietly() {
        try {
            // First, execute any remaining batch operations
            if (tickInsertStatement != null && batchCount > 0) {
                // Always execute remaining batch, regardless of batchCount value
                tickInsertStatement.executeBatch();
                log.debug("Executed final batch of {} ticks before pause", batchCount);
                batchCount = 0; // Reset batch counter after execution
            }
            if (tickInsertStatement != null) {
                tickInsertStatement.close();
                tickInsertStatement = null;
            }
            
            // Perform WAL checkpoint to ensure all changes are in the main database
            if (connection != null && !connection.isClosed()) {
                try (Statement st = connection.createStatement()) {
                    // Force all pending changes to be written
                    st.execute("PRAGMA wal_checkpoint(FULL)");
                    log.debug("WAL checkpoint completed before closing");
                    
                    // Force a final checkpoint to ensure all data is flushed
                    st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                    log.debug("Final WAL truncate checkpoint completed");
                    
                    // Ensure WAL mode is properly closed and files are released
                    st.execute("PRAGMA journal_mode=DELETE");
                    log.debug("WAL mode disabled, journal mode set to DELETE");
                    
                    // Final checkpoint after disabling WAL to ensure all data is in main file
                    st.execute("PRAGMA wal_checkpoint(FULL)");
                    log.debug("Final checkpoint after WAL disable completed");
                } catch (Exception e) {
                    log.warn("Error during WAL checkpoint before closing: {}", e.getMessage());
                }
            }
            
            // Close the connection
            if (connection != null) {
                connection.close();
                connection = null;
                log.debug("Database connection closed");
            }
            
            // Note: Raw database is managed by PersistenceService, not by us
            // No need to close raw database connections
            
        } catch (Exception e) {
            log.warn("Error closing database cleanly: {}", e.getMessage());
        }
    }
    
    /**
     * Close any open raw database connections to ensure WAL files are properly closed.
     * Note: We only close our own debug database, not the raw database which is managed by PersistenceService.
     */
    private void closeRawDatabaseConnections() {
        // The raw database is managed by PersistenceService, not by us
        // We should not try to close it as it may be in use by other services
        log.debug("Skipping raw database connection close - managed by PersistenceService");
    }
}