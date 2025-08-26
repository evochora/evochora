package org.evochora.server.indexer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.internal.LinearizedProgramArtifact;
import org.evochora.runtime.isa.Instruction;
import org.evochora.server.contracts.debug.PreparedTickState;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.contracts.raw.RawTickState;
import org.junit.jupiter.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Helper-Klasse für DebugIndexer-Integrationstests.
 * Stellt zuverlässiges Setup und Teardown für Datenbank-Tests bereit.
 * 
 * Verwendung:
 * try (DebugIndexerTestHelper helper = new DebugIndexerTestHelper()) {
 *     helper.setupRawDatabase();
 *     helper.setupDebugDatabase();
 *     DebugIndexer indexer = helper.createIndexer();
 *     helper.processTick(indexer, 1L);
 *     helper.verifyPreparedTick(1L);
 * }
 * 
 * Diese Klasse eliminiert Race-Conditions und macht Tests zuverlässig.
 */
@Tag("integration")
class DebugIndexerTestHelper implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DebugIndexerTestHelper.class);

    private final String rawJdbcUrl;
    private final String debugJdbcUrl;
    private final ObjectMapper mapper;
    private Connection rawConn;
    private Connection debugConn;
    private ProgramArtifact testArtifact;

    /**
     * Erstellt einen neuen Test-Helper mit eindeutigen Datenbank-URLs.
     */
    public DebugIndexerTestHelper() {
        // Verwende eindeutige URLs für jeden Test-Lauf
        String uniqueId = String.valueOf(System.currentTimeMillis());
        this.rawJdbcUrl = "jdbc:sqlite:file:memdb_raw_" + uniqueId + "?mode=memory&cache=shared";
        this.debugJdbcUrl = "jdbc:sqlite:file:memdb_debug_" + uniqueId + "?mode=memory&cache=shared";
        this.mapper = new ObjectMapper();
    }

    /**
     * Richtet die Raw-Datenbank mit allen erforderlichen Tabellen und Test-Daten ein.
     */
    public void setupRawDatabase() throws Exception {
        log.debug("Setting up raw database: {}", rawJdbcUrl);
        
        // Initialisiere Instructions (wichtig für den Compiler)
        Instruction.init();
        
        // Kompiliere ein einfaches Test-Artefakt
        Compiler compiler = new Compiler();
        testArtifact = compiler.compile(List.of("L:", "NOP"), "test.s");

        // Erstelle einen rohen Tick-Zustand
        RawOrganismState rawOrganism = new RawOrganismState(
                1, null, 0L, testArtifact.programId(), new int[]{0,0},
                new int[]{0,0}, new int[]{1,0}, Collections.emptyList(), 0, 1000,
                Collections.singletonList(42), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                new java.util.ArrayDeque<>(), new java.util.ArrayDeque<>(), new java.util.ArrayDeque<>(),
                false, false, null, false, new int[]{0,0}, new int[]{1,0}
        );
        RawTickState rawTick = new RawTickState(1L, List.of(rawOrganism), Collections.emptyList());

        // Verbinde zur Raw-Datenbank und erstelle Tabellen
        rawConn = DriverManager.getConnection(rawJdbcUrl);
        try (Statement st = rawConn.createStatement()) {
            st.execute("CREATE TABLE program_artifacts (program_id TEXT PRIMARY KEY, artifact_json TEXT)");
            st.execute("CREATE TABLE raw_ticks (tick_number INTEGER PRIMARY KEY, tick_data_json TEXT)");
            st.execute("CREATE TABLE simulation_metadata (key TEXT PRIMARY KEY, value TEXT)");
        }

        // Füge Test-Daten ein
        try (PreparedStatement ps = rawConn.prepareStatement("INSERT INTO program_artifacts VALUES (?,?)")) {
            ps.setString(1, testArtifact.programId());
            LinearizedProgramArtifact linearized = testArtifact.toLinearized(new int[]{10, 10});
            ps.setString(2, mapper.writeValueAsString(linearized));
            ps.executeUpdate();
        }

        try (PreparedStatement ps = rawConn.prepareStatement("INSERT INTO raw_ticks VALUES (?,?)")) {
            ps.setLong(1, 1L);
            ps.setString(2, mapper.writeValueAsString(rawTick));
            ps.executeUpdate();
        }

        try (PreparedStatement ps = rawConn.prepareStatement("INSERT INTO simulation_metadata VALUES (?,?)")) {
            ps.setString(1, "worldShape");
            ps.setString(2, mapper.writeValueAsString(new int[]{10, 10}));
            ps.executeUpdate();
        }

        log.debug("Raw database setup completed successfully");
    }

    /**
     * Fügt zusätzliche Ticks zur Raw-Datenbank hinzu.
     * Nützlich für Tests, die mehrere Ticks verarbeiten sollen.
     */
    public void addAdditionalTicks(int... tickNumbers) throws Exception {
        log.debug("Adding additional ticks: {}", java.util.Arrays.toString(tickNumbers));
        
        for (int tickNumber : tickNumbers) {
            // Erstelle einen rohen Tick-Zustand für den zusätzlichen Tick
            RawOrganismState rawOrganism = new RawOrganismState(
                    tickNumber, null, 0L, testArtifact.programId(), new int[]{0,0},
                    new int[]{0,0}, new int[]{1,0}, Collections.emptyList(), 0, 1000,
                    Collections.singletonList(42), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    new java.util.ArrayDeque<>(), new java.util.ArrayDeque<>(), new java.util.ArrayDeque<>(),
                    false, false, null, false, new int[]{0,0}, new int[]{1,0}
            );
            RawTickState rawTick = new RawTickState(tickNumber, List.of(rawOrganism), Collections.emptyList());
            
            // Füge den Tick zur Datenbank hinzu
            try (PreparedStatement ps = rawConn.prepareStatement("INSERT INTO raw_ticks VALUES (?,?)")) {
                ps.setLong(1, tickNumber);
                ps.setString(2, mapper.writeValueAsString(rawTick));
                ps.executeUpdate();
            }
        }
        
        log.debug("Additional ticks added successfully");
    }

    /**
     * Richtet die Debug-Datenbank mit allen erforderlichen Tabellen ein.
     * WICHTIG: Diese Methode erstellt die prepared_ticks Tabelle VOR dem Start des Indexers,
     * was Race-Conditions eliminiert.
     */
    public void setupDebugDatabase() throws Exception {
        log.debug("Setting up debug database: {}", debugJdbcUrl);
        
        // Verbinde zur Debug-Datenbank und erstelle ALLE erforderlichen Tabellen
        debugConn = DriverManager.getConnection(debugJdbcUrl);
        try (Statement st = debugConn.createStatement()) {
            // Erstelle die prepared_ticks Tabelle VOR dem Start des Indexers
            // Das eliminiert die Race-Condition!
            st.execute("CREATE TABLE IF NOT EXISTS prepared_ticks (tick_number INTEGER PRIMARY KEY, tick_data_json TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS simulation_metadata (key TEXT PRIMARY KEY, value TEXT)");
        }

        log.debug("Debug database setup completed successfully");
    }

    /**
     * Erstellt einen neuen DebugIndexer mit den konfigurierten Datenbank-URLs.
     */
    public DebugIndexer createIndexer() {
        log.debug("Creating DebugIndexer with batch size 1 for reliable testing");
        return new DebugIndexer(rawJdbcUrl, debugJdbcUrl, 1); // Batch size = 1 für zuverlässige Tests
    }

    /**
     * Verarbeitet einen spezifischen Tick mit dem gegebenen Indexer.
     * Wartet zuverlässig auf den Abschluss der Verarbeitung.
     */
    public void processTick(DebugIndexer indexer, long tickNumber) throws Exception {
        log.debug("Processing tick {} with indexer", tickNumber);
        
        // Starte den Indexer nur beim ersten Aufruf
        if (!indexer.isRunning()) {
            indexer.start();
            waitForIndexerStarted(indexer);
        }
        
        // Warte auf die Verarbeitung des spezifischen Ticks
        waitForTickProcessed(tickNumber);
        
        log.debug("Tick {} processing completed successfully", tickNumber);
    }

    /**
     * Wartet darauf, dass der Indexer gestartet ist.
     */
    private void waitForIndexerStarted(DebugIndexer indexer) throws InterruptedException {
        int maxWaitTime = 1000; // 1 Sekunde maximal
        int waitInterval = 50; // Alle 50ms prüfen
        
        for (int waited = 0; waited < maxWaitTime; waited += waitInterval) {
            if (indexer.isRunning()) {
                log.debug("Indexer started after {}ms", waited);
                return;
            }
            Thread.sleep(waitInterval);
        }
        
        throw new RuntimeException("Indexer did not start within " + maxWaitTime + "ms");
    }

    /**
     * Wartet darauf, dass ein spezifischer Tick verarbeitet wurde.
     */
    private void waitForTickProcessed(long tickNumber) throws Exception {
        int maxWaitTime = 2000; // 2 Sekunden maximal
        int waitInterval = 100; // Alle 100ms prüfen
        
        for (int waited = 0; waited < maxWaitTime; waited += waitInterval) {
            try (Connection conn = DriverManager.getConnection(debugJdbcUrl);
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM prepared_ticks WHERE tick_number = " + tickNumber)) {
                
                if (rs.next() && rs.getInt(1) > 0) {
                    log.debug("Tick {} processed after {}ms", tickNumber, waited);
                    return;
                }
            } catch (Exception e) {
                // Tabelle existiert noch nicht oder ist noch nicht bereit
                log.debug("Tick {} not ready yet, retry in {}ms", tickNumber, waitInterval);
            }
            
            Thread.sleep(waitInterval);
        }
        
        throw new RuntimeException("Tick " + tickNumber + " was not processed within " + maxWaitTime + "ms");
    }

    /**
     * Überprüft, dass ein vorbereiteter Tick korrekt in der Debug-Datenbank gespeichert wurde.
     */
    public void verifyPreparedTick(long tickNumber) throws Exception {
        log.debug("Verifying prepared tick {}", tickNumber);
        
        try (Connection conn = DriverManager.getConnection(debugJdbcUrl);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT tick_data_json FROM prepared_ticks WHERE tick_number = " + tickNumber)) {

            assertThat(rs.next()).isTrue();
            String preparedJson = rs.getString(1);
            
            log.debug("Prepared JSON for tick {}: {}", tickNumber, preparedJson);
            
            PreparedTickState preparedTick = mapper.readValue(preparedJson, PreparedTickState.class);

            // Validiere die Transformation
            assertThat(preparedTick.tickNumber()).isEqualTo(tickNumber);
            // Der Organismus hat die ID des Tick-Numbers
            assertThat(preparedTick.organismDetails()).containsKey(String.valueOf(tickNumber));
            
            log.debug("Tick {} verification completed successfully", tickNumber);
        }
    }

    /**
     * Gibt die Raw-Datenbank-URL zurück (für Debugging).
     */
    public String getRawJdbcUrl() {
        return rawJdbcUrl;
    }

    /**
     * Gibt die Debug-Datenbank-URL zurück (für Debugging).
     */
    public String getDebugJdbcUrl() {
        return debugJdbcUrl;
    }

    /**
     * Räumt alle Ressourcen sauber auf.
     */
    @Override
    public void close() throws Exception {
        log.debug("Cleaning up test helper resources");
        
        if (rawConn != null && !rawConn.isClosed()) {
            rawConn.close();
        }
        
        if (debugConn != null && !debugConn.isClosed()) {
            debugConn.close();
        }
        
        log.debug("Test helper cleanup completed");
    }
}
