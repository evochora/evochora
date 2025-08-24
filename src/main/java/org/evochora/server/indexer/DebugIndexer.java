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
import org.evochora.compiler.internal.LinearizedProgramArtifact;
import org.evochora.server.indexer.RuntimeDisassembler;
import org.evochora.runtime.api.DisassembledInstruction;
import org.evochora.runtime.api.DisassembledArgument;

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
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String rawDbPath;
    private final String debugDbPath;
    private final SourceAnnotator sourceAnnotator = new SourceAnnotator();

    private Map<String, ProgramArtifact> artifacts = new HashMap<>();
    private int[] worldShape = new int[]{0, 0};
    
    // Cache für Artifact-Validität pro Organismus (programId_organismId -> ArtifactValidity)
    private final Map<String, ArtifactValidity> validityCache = new HashMap<>();

    public DebugIndexer(String rawDbPath) {
        this.rawDbPath = rawDbPath;
        this.debugDbPath = rawDbPath.replace("_raw.sqlite", "_debug.sqlite");
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

        return latestRawDb.map(path -> new DebugIndexer(path.toAbsolutePath().toString()));
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            setupDebugDatabase();
            thread.start();
        }
    }

    @Override
    public void pause() {
        paused.set(true);
    }

    @Override
    public void resume() {
        paused.set(false);
    }

    @Override
    public void shutdown() {
        running.set(false);
        thread.interrupt();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isPaused() {
        return paused.get();
    }

    private void setupDebugDatabase() {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + debugDbPath);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS prepared_ticks (tick_number INTEGER PRIMARY KEY, tick_data_json TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS simulation_metadata (key TEXT PRIMARY KEY, value TEXT)");
        } catch (Exception e) {
            throw new RuntimeException("Failed to setup debug database", e);
        }
    }

    @Override
    public void run() {
        log.info("DebugIndexer started. Raw DB: '{}', Debug DB: '{}'", rawDbPath, debugDbPath);
        try {
            loadInitialData();
            long lastProcessedTick = getLastProcessedTick();
            log.info("Resuming indexing from tick {}", lastProcessedTick + 1);

            while (running.get()) {
                if (paused.get()) {
                    Thread.onSpinWait();
                    continue;
                }

                boolean processed = processNextTick(lastProcessedTick + 1);
                if (processed) {
                    lastProcessedTick++;
                } else {
                    Thread.sleep(1000);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("DebugIndexer error", e);
        } finally {
            log.info("DebugIndexer stopped");
        }
    }

    private void loadInitialData() {
        try (Connection rawConn = DriverManager.getConnection("jdbc:sqlite:" + rawDbPath);
             Statement st = rawConn.createStatement()) {
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
            try (ResultSet rs = st.executeQuery("SELECT key, value FROM simulation_metadata WHERE key = 'worldShape'")) {
                if (rs.next()) {
                    this.worldShape = objectMapper.readValue(rs.getString(2), int[].class);
                }
            }
        } catch (Exception e) {
            log.error("Failed to load initial data from raw database", e);
        }
    }

    private boolean processNextTick(long tickToProcess) {
        try (Connection rawConn = DriverManager.getConnection("jdbc:sqlite:" + rawDbPath);
             PreparedStatement ps = rawConn.prepareStatement("SELECT tick_data_json FROM raw_ticks WHERE tick_number = ?")) {

            ps.setLong(1, tickToProcess);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String rawTickJson = rs.getString(1);
                    RawTickState rawTickState = objectMapper.readValue(rawTickJson, new TypeReference<>() {
                    });
                    PreparedTickState preparedTick = transformRawToPrepared(rawTickState);
                    writePreparedTick(preparedTick);
                    log.info("Indexed tick {}", tickToProcess);
                    return true;
                }
            }
        } catch (Exception e) {
            log.error("Failed to process tick {}", tickToProcess, e);
        }
        return false;
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
            PreparedTickState.OrganismDetails organismDetails = buildOrganismDetails(o);
            details.put(String.valueOf(o.id()), organismDetails);
        }

        return new PreparedTickState("debug", raw.tickNumber(), meta, worldState, details);
    }

    /**
     * Zentrale Methode zum Erstellen aller Organismus-Details.
     * Koordiniert die Validierung und ruft alle Builder auf.
     */
    private PreparedTickState.OrganismDetails buildOrganismDetails(RawOrganismState o) {
        ProgramArtifact artifact = this.artifacts.get(o.programId());
        ArtifactValidity validity = checkArtifactValidity(o, artifact);
        
        var basicInfo = new PreparedTickState.BasicInfo(o.id(), o.programId(), o.parentId(), o.birthTick(), o.er(), toList(o.ip()), toList(o.dv()));
        var nextInstruction = buildNextInstruction(o, artifact, validity);
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

    private PreparedTickState.NextInstruction buildNextInstruction(RawOrganismState o, ProgramArtifact artifact, ArtifactValidity validity) {
        try {
            // Verwende den RuntimeDisassembler für Runtime-only Disassembly
            RuntimeDisassembler disassembler = RuntimeDisassembler.INSTANCE;
            DisassembledInstruction disassembled = disassembler.disassembleRuntimeOnly(o);
            
            // Bestimme den runtimeStatus basierend auf der Validität
            String runtimeStatus = determineRuntimeStatus(validity);
            
            // Erstelle die NextInstruction
            return new PreparedTickState.NextInstruction(
                formatDisassembly(disassembled),
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
    private String formatDisassembly(DisassembledInstruction disassembled) {
        StringBuilder sb = new StringBuilder();
        sb.append(disassembled.opcodeName());
        
        if (!disassembled.arguments().isEmpty()) {
            sb.append(" ");
            for (int i = 0; i < disassembled.arguments().size(); i++) {
                if (i > 0) sb.append(", ");
                DisassembledArgument arg = disassembled.arguments().get(i);
                sb.append(arg.fullDisplayValue());
            }
        }
        
        return sb.toString();
    }

    private PreparedTickState.InternalState buildInternalState(RawOrganismState o, ProgramArtifact artifact, ArtifactValidity validity) {
        List<PreparedTickState.RegisterValue> drs = new ArrayList<>();
        for (int i = 0; i < o.drs().size(); i++) drs.add(new PreparedTickState.RegisterValue("%DR" + i, null, formatValue(o.drs().get(i))));

        List<PreparedTickState.RegisterValue> prs = new ArrayList<>();
        for (int i = 0; i < o.prs().size(); i++) prs.add(new PreparedTickState.RegisterValue("%PR" + i, null, formatValue(o.prs().get(i))));

        List<PreparedTickState.RegisterValue> lrs = new ArrayList<>();
        for (int i = 0; i < o.lrs().size(); i++) lrs.add(new PreparedTickState.RegisterValue("%LR" + i, null, formatValue(o.lrs().get(i))));

        List<String> ds = o.dataStack().stream().map(this::formatValue).toList();
        List<String> ls = o.locationStack().stream().map(this::formatValue).toList();
        List<String> cs = formatCallStack(o, artifact);

        return new PreparedTickState.InternalState(drs, prs, lrs, ds, ls, cs, o.dps().stream().map(this::toList).toList());
    }

    private PreparedTickState.SourceView buildSourceView(RawOrganismState o, ProgramArtifact artifact, ArtifactValidity validity) {
        // SourceView ist nur verfügbar, wenn das Artifact gültig ist
        if (artifact == null || validity == ArtifactValidity.NONE || validity == ArtifactValidity.INVALID) {
            return null;
        }

        // TODO: Implementiere die Logik zur Handhabung von compiler-generiertem Code
        // Für jetzt geben wir null zurück, da wir die Source-Mapping-Informationen noch nicht haben
        return null;
    }

    private List<String> formatCallStack(RawOrganismState o, ProgramArtifact artifact) {
        if (o.callStack() == null || o.callStack().isEmpty()) return Collections.emptyList();

        if (artifact == null) {
            // Fallback, wenn kein Artefakt verfügbar ist
            return o.callStack().stream().map(SerializableProcFrame::procName).collect(Collectors.toList());
        }

        // Vollständige Auflösung
        // HINWEIS: Dies ist eine komplexe Logik, die den Stack durchlaufen und Bindungen auflösen muss.
        // Vorerst eine vereinfachte Version.
        return o.callStack().stream()
                .map(frame -> {
                    String procName = frame.procName();
                    List<String> paramNames = artifact.procNameToParamNames().get(procName.toUpperCase());
                    if (paramNames == null || paramNames.isEmpty()) {
                        return procName;
                    }
                    //... hier würde die vollständige Auflösung der Parameter stattfinden
                    return procName + " (...)";
                })
                .collect(Collectors.toList());
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

    private void writePreparedTick(PreparedTickState preparedTick) throws Exception {
        try (Connection debugConn = DriverManager.getConnection("jdbc:sqlite:" + debugDbPath);
             PreparedStatement ps = debugConn.prepareStatement("INSERT OR REPLACE INTO prepared_ticks(tick_number, tick_data_json) VALUES (?, ?)")) {
            ps.setLong(1, preparedTick.tickNumber());
            ps.setString(2, objectMapper.writeValueAsString(preparedTick));
            ps.executeUpdate();
        }
    }

    private long getLastProcessedTick() {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + debugDbPath);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(tick_number) FROM prepared_ticks")) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (Exception e) {
            // Erwartet, wenn die Datei noch nicht existiert.
        }
        return -1;
    }
}