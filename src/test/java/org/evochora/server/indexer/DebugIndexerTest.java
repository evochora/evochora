package org.evochora.server.indexer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.internal.LinearizedProgramArtifact;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.IEnvironmentReader;
import org.evochora.server.contracts.debug.PreparedTickState;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.contracts.raw.RawTickState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import org.evochora.runtime.services.Disassembler;
import org.evochora.runtime.services.DisassemblyData;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.isa.InstructionSignature;
import org.evochora.runtime.isa.InstructionArgumentType;
import org.evochora.runtime.model.Molecule;

class DebugIndexerTest {

    private static final Logger log = LoggerFactory.getLogger(DebugIndexerTest.class);

    @TempDir
    Path tempDir;

    @BeforeAll
    static void init() {
        Instruction.init();
    }


    @Test
    void indexer_readsRawDb_and_writesPreparedDb() throws Exception {
        // 1. Arrange: Use in-memory databases
        String rawJdbcUrl = "jdbc:sqlite:file:memdb_raw_debug?mode=memory&cache=shared";
        String debugJdbcUrl = "jdbc:sqlite:file:memdb_debug_debug?mode=memory&cache=shared";
        ObjectMapper mapper = new ObjectMapper();

        // Kompiliere ein einfaches Artefakt
        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(List.of("L:", "NOP"), "test.s");

        // Erstelle einen rohen Tick-Zustand
        RawOrganismState rawOrganism = new RawOrganismState(
                1, null, 0L, artifact.programId(), new int[]{0,0},
                new int[]{0,0}, new int[]{1,0}, Collections.emptyList(), 0, 1000,
                Collections.singletonList(42), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                new java.util.ArrayDeque<>(), new java.util.ArrayDeque<>(), new java.util.ArrayDeque<>(),
                false, false, null, false, new int[]{0,0}, new int[]{1,0}
        );
        RawTickState rawTick = new RawTickState(1L, List.of(rawOrganism), Collections.emptyList());

        // Befülle die Roh-Datenbank
        Connection rawConn = DriverManager.getConnection(rawJdbcUrl);
        try (Statement st = rawConn.createStatement()) {
            st.execute("CREATE TABLE program_artifacts (program_id TEXT PRIMARY KEY, artifact_json TEXT)");
            st.execute("CREATE TABLE raw_ticks (tick_number INTEGER PRIMARY KEY, tick_data_json TEXT)");
            st.execute("CREATE TABLE simulation_metadata (key TEXT PRIMARY KEY, value TEXT)");
            
            // ProgramArtifact in die Roh-Datenbank einfügen
            try (PreparedStatement ps = rawConn.prepareStatement("INSERT INTO program_artifacts VALUES (?,?)")) {
                ps.setString(1, artifact.programId());
                // Verwende das linearisierte Format für Jackson-Serialisierung
                LinearizedProgramArtifact linearized = artifact.toLinearized(new int[]{10, 10});
                ps.setString(2, mapper.writeValueAsString(linearized));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = rawConn.prepareStatement("INSERT INTO raw_ticks VALUES (?,?)")) {
                ps.setLong(1, 1); // Tick 1, da DebugIndexer bei Tick 1 startet
                ps.setString(2, mapper.writeValueAsString(rawTick));
                ps.executeUpdate();
            }
            // Add worldShape metadata that DebugIndexer expects
            try (PreparedStatement ps = rawConn.prepareStatement("INSERT INTO simulation_metadata VALUES (?,?)")) {
                ps.setString(1, "worldShape");
                ps.setString(2, mapper.writeValueAsString(new int[]{10, 10}));
                ps.executeUpdate();
            }
        }
        
        // Erstelle auch die Debug-Datenbank mit der prepared_ticks Tabelle
        // Verwende IF NOT EXISTS um Race-Conditions zu vermeiden
        try (Connection debugConn = DriverManager.getConnection(debugJdbcUrl);
             Statement st = debugConn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS prepared_ticks (tick_number INTEGER PRIMARY KEY, tick_data_json TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS simulation_metadata (key TEXT PRIMARY KEY, value TEXT)");
        }

        try {
            // 2. Act: Starte den Indexer und lass ihn einen Tick verarbeiten
            DebugIndexer indexer = new DebugIndexer(rawJdbcUrl, debugJdbcUrl, 1000);
            indexer.start();
            
            // Warte bis der Indexer die Verarbeitung abgeschlossen hat
            // Verwende eine robustere Warteschleife mit Retry-Logik
            int maxRetries = 20; // Erhöhe die Anzahl der Versuche
            int retryCount = 0;
            boolean processingComplete = false;
            
            while (retryCount < maxRetries && !processingComplete) {
                Thread.sleep(300); // Kürzere Wartezeiten für schnellere Reaktion
                
                try (Connection conn = DriverManager.getConnection(debugJdbcUrl);
                     Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM prepared_ticks WHERE tick_number = 1")) {
                    
                    if (rs.next() && rs.getInt(1) > 0) {
                        processingComplete = true;
                        log.debug("Processing completed after {} retries", retryCount + 1);
                        break;
                    }
                } catch (Exception e) {
                    // Tabelle existiert noch nicht oder ist noch nicht bereit
                    log.debug("Table not ready yet, retry {}: {}", retryCount + 1, e.getMessage());
                }
                
                retryCount++;
            }
            
            if (!processingComplete) {
                log.warn("Processing did not complete within expected time, proceeding anyway");
            }
            
            // Gib dem Indexer noch etwas Zeit für finale Verarbeitung
            Thread.sleep(300);
            indexer.shutdown();

            // 3. Assert: Überprüfe die Debug-Datenbank
            try (Connection conn = DriverManager.getConnection(debugJdbcUrl);
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT tick_data_json FROM prepared_ticks WHERE tick_number = 1")) {

                assertThat(rs.next()).isTrue();
                String preparedJson = rs.getString(1);
            System.out.println("Prepared JSON: " + preparedJson);
                PreparedTickState preparedTick = mapper.readValue(preparedJson, PreparedTickState.class);

                assertThat(preparedTick.tickNumber()).isEqualTo(1);
                assertThat(preparedTick.organismDetails()).containsKey("1");
                // Weitere, detailliertere Assertions können hier hinzugefügt werden,
                // sobald die Transformationslogik vollständig ist.
            }
        } finally {
            rawConn.close();
        }
    }

    /**
     * Simuliert die aktuelle Runtime-Methode (direkte Interpretation).
     */
    private void simulateDirectRuntimeMethod(MockEnvironment env, int[] ip) {
        try {
            Molecule opcodeMolecule = env.getMolecule(ip);
            if (opcodeMolecule != null) {
                int opcodeId = opcodeMolecule.toInt();
                String opcodeName = Instruction.getInstructionNameById(opcodeId);
                
                // Simuliere Argument-Lesen (vereinfacht)
                if (!"UNKNOWN".equals(opcodeName)) {
                    var signatureOpt = Instruction.getSignatureById(opcodeId);
                    if (signatureOpt.isPresent()) {
                        InstructionSignature sig = signatureOpt.get();
                        for (InstructionArgumentType argType : sig.argumentTypes()) {
                            // Simuliere das Lesen eines Arguments
                            int[] nextPos = env.getProperties().getNextPosition(ip, new int[]{1, 0});
                            Molecule argMolecule = env.getMolecule(nextPos);
                            if (argMolecule != null) {
                                int argValue = argMolecule.toInt();
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignoriere Fehler im Test
        }
    }
    
    /**
     * Mock-Environment für den Performance-Test.
     */
    private static class MockEnvironment implements IEnvironmentReader {
        private final EnvironmentProperties properties;
        
        public MockEnvironment(EnvironmentProperties properties) {
            this.properties = properties;
        }
        
        @Override
        public org.evochora.runtime.model.Molecule getMolecule(int[] coordinates) {
            // Vereinfachte Implementierung für den Test
            // In der Realität würde hier die echte Logik stehen
            return new org.evochora.runtime.model.Molecule(42, 0); // Test-Wert (value, type)
        }
        
        @Override
        public int[] getShape() {
            return properties.getWorldShape();
        }
        
        @Override
        public EnvironmentProperties getProperties() {
            return properties;
        }
    }
}