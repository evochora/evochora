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

public class DebugIndexer implements IControllable, Runnable {

    private static final Logger log = LoggerFactory.getLogger(DebugIndexer.class);

    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String rawDbPath;
    private final String debugDbPath;
    private final SourceAnnotator sourceAnnotator = new SourceAnnotator();

    private Map<String, ProgramArtifact> artifacts = new HashMap<>();
    private int[] worldShape = new int[]{0, 0};

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
                    ProgramArtifact artifact = objectMapper.readValue(json, ProgramArtifact.class);
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
            ProgramArtifact artifact = this.artifacts.get(o.programId());

            var basicInfo = new PreparedTickState.BasicInfo(o.id(), o.programId(), o.parentId(), o.birthTick(), o.er(), toList(o.ip()), toList(o.dv()));
            var nextInstruction = buildNextInstruction(o, artifact);
            var internalState = buildInternalState(o, artifact);
            var sourceView = buildSourceView(o, artifact, nextInstruction);

            details.put(String.valueOf(o.id()), new PreparedTickState.OrganismDetails(basicInfo, nextInstruction, internalState, sourceView));
        }

        return new PreparedTickState("debug", raw.tickNumber(), meta, worldState, details);
    }

    private PreparedTickState.NextInstruction buildNextInstruction(RawOrganismState o, ProgramArtifact artifact) {
        // Diese Methode ist ein Platzhalter und wird in einem späteren Schritt implementiert.
        // HINWEIS: Erfordert die Rekonstruktion eines Teils des Laufzeitzustands,
        // um den Disassembler sicher aufrufen zu können.
        return new PreparedTickState.NextInstruction("Disassembly not yet implemented", null, null, artifact == null ? "CODE_UNAVAILABLE" : "OK");
    }

    private PreparedTickState.InternalState buildInternalState(RawOrganismState o, ProgramArtifact artifact) {
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

    private PreparedTickState.SourceView buildSourceView(RawOrganismState o, ProgramArtifact artifact, PreparedTickState.NextInstruction ni) {
        if (artifact == null || ni.sourceFile() == null || ni.sourceLine() == null) {
            return null;
        }

        // Finde die Quellcodezeilen für die relevante Datei
        String sourceFileName = ni.sourceFile();
        List<String> sourceLines = artifact.sources().get(sourceFileName);
        if (sourceLines == null) {
            // Fallback auf Basename, falls der Pfad nicht exakt übereinstimmt
            Path p = Paths.get(sourceFileName);
            sourceLines = artifact.sources().get(p.getFileName().toString());
        }
        if (sourceLines == null) return null;

        int currentLineNumber = ni.sourceLine();

        // TODO: Implementiere die Logik zur Handhabung von compiler-generiertem Code
        List<PreparedTickState.SourceLine> lines = new ArrayList<>();
        for (int i = 0; i < sourceLines.size(); i++) {
            lines.add(new PreparedTickState.SourceLine(i + 1, sourceLines.get(i), (i + 1) == currentLineNumber, Collections.emptyList(), Collections.emptyList()));
        }

        List<PreparedTickState.InlineSpan> spans = sourceAnnotator.annotate(o, artifact, sourceLines.get(currentLineNumber - 1), currentLineNumber);

        return new PreparedTickState.SourceView(sourceFileName, currentLineNumber, lines, spans);
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