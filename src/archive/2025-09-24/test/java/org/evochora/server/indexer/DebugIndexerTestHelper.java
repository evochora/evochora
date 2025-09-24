package org.evochora.server.indexer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.internal.LinearizedProgramArtifact;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.server.contracts.debug.PreparedTickState;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.contracts.raw.RawTickState;
import org.junit.jupiter.api.Tag;
import org.evochora.server.config.SimulationConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A helper class for creating reliable integration tests for the {@link DebugIndexer}.
 * It encapsulates the logic for setting up and tearing down in-memory databases,
 * populating them with test data, and running the indexer in a controlled manner.
 * <p>
 * This class implements {@link AutoCloseable} to ensure resources are cleaned up
 * when used with a try-with-resources statement in tests.
 * <p>
 * Example Usage:
 * <pre>
 * {@code
 * try (DebugIndexerTestHelper helper = new DebugIndexerTestHelper()) {
 *     helper.setupRawDatabase();
 *     helper.setupDebugDatabase();
 *     DebugIndexer indexer = helper.createIndexer();
 *     helper.processTick(indexer, 1L);
 *     helper.verifyPreparedTick(1L);
 * }
 * }
 * </pre>
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
     * Creates a new test helper, initializing unique in-memory database URLs for test isolation.
     */
    public DebugIndexerTestHelper() {
        // Verwende eindeutige URLs für jeden Test-Lauf
        String uniqueId = String.valueOf(System.currentTimeMillis());
        this.rawJdbcUrl = "jdbc:sqlite:file:memdb_raw_" + uniqueId + "?mode=memory&cache=shared";
        this.debugJdbcUrl = "jdbc:sqlite:file:memdb_debug_" + uniqueId + "?mode=memory&cache=shared";
        this.mapper = new ObjectMapper();
    }

    /**
     * Sets up the 'raw' source database with all required tables and populates it with
     * default test data for one tick and one program artifact.
     * @throws Exception if database setup or data insertion fails.
     */
    public void setupRawDatabase() throws Exception {
        log.debug("Setting up raw database: {}", rawJdbcUrl);
        
        // Initialisiere Instructions (wichtig für den Compiler)
        Instruction.init();
        
        // Kompiliere ein einfaches Test-Artefakt
        Compiler compiler = new Compiler();
        testArtifact = compiler.compile(List.of("L:", "NOP"), "test.s", new EnvironmentProperties(new int[]{10, 10}, true));

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
            LinearizedProgramArtifact linearized = testArtifact.toLinearized(new EnvironmentProperties(new int[]{10, 10}, true));
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
        
        try (PreparedStatement ps = rawConn.prepareStatement("INSERT INTO simulation_metadata VALUES (?,?)")) {
            ps.setString(1, "isToroidal");
            ps.setString(2, mapper.writeValueAsString(true));
            ps.executeUpdate();
        }

        log.debug("Raw database setup completed successfully");
    }

    /**
     * Adds additional mock ticks to the raw database for testing multi-tick processing.
     * @param tickNumbers The numbers of the ticks to add.
     * @throws Exception if database insertion fails.
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
     * Sets up the 'debug' destination database with the required table schema.
     * This is called before the indexer is started to avoid race conditions.
     * @throws Exception if database setup fails.
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
     * Creates a new {@link DebugIndexer} instance configured for reliable testing (batch size 1).
     * @return A new DebugIndexer.
     */
    public DebugIndexer createIndexer() {
        log.debug("Creating DebugIndexer with batch size 1 for reliable testing");
        SimulationConfiguration.IndexerServiceConfig config = new SimulationConfiguration.IndexerServiceConfig();
        config.batchSize = 1; // Batch size = 1 für zuverlässige Tests
        return new DebugIndexer(rawJdbcUrl, debugJdbcUrl, config);
    }

    /**
     * Runs the indexer and waits reliably for a specific tick to be processed.
     * @param indexer The indexer instance to run.
     * @param tickNumber The tick to wait for.
     * @throws Exception if the tick is not processed within the timeout.
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
     * Waits for the indexer thread to enter the running state.
     * @param indexer The indexer instance.
     * @throws InterruptedException if the thread is interrupted.
     */
    private void waitForIndexerStarted(DebugIndexer indexer) throws InterruptedException {
        assertTrue(waitForCondition(
            indexer::isRunning,
            1000,
            "indexer to start"
        ));
        log.debug("Indexer started successfully");
    }

    /**
     * Waits by polling the debug database until the specified tick number appears.
     * @param tickNumber The tick to wait for.
     * @throws Exception if the tick does not appear within the timeout.
     */
    private void waitForTickProcessed(long tickNumber) throws Exception {
        assertTrue(waitForCondition(
            () -> {
                try (Connection conn = DriverManager.getConnection(debugJdbcUrl);
                     Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM prepared_ticks WHERE tick_number = " + tickNumber)) {
                    
                    return rs.next() && rs.getInt(1) > 0;
                } catch (Exception e) {
                    // Tabelle existiert noch nicht oder ist noch nicht bereit
                    log.debug("Tick {} not ready yet, retry in next iteration", tickNumber);
                    return false;
                }
            },
            2000,
            "tick " + tickNumber + " to be processed"
        ));
        log.debug("Tick {} processed successfully", tickNumber);
    }

    /**
     * Wait for a condition to be true, checking every 10ms
     * @param condition The condition to wait for
     * @param timeoutMs Maximum time to wait in milliseconds
     * @param description Description of what we're waiting for
     * @return true if condition was met, false if timeout occurred
     */
    private boolean waitForCondition(BooleanSupplier condition, long timeoutMs, String description) {
        long startTime = System.currentTimeMillis();
        long checkInterval = 10; // Check every 10ms for faster response
        
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                log.warn("Timeout waiting for: {}", description);
                return false;
            }
            try {
                Thread.sleep(checkInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * Connects to the debug database and verifies that the data for the specified tick is present and correct.
     * @param tickNumber The tick number to verify.
     * @throws Exception if database access or deserialization fails.
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
     * Gets the JDBC URL for the raw database.
     * @return The JDBC URL string.
     */
    public String getRawJdbcUrl() {
        return rawJdbcUrl;
    }

    /**
     * Gets the JDBC URL for the debug database.
     * @return The JDBC URL string.
     */
    public String getDebugJdbcUrl() {
        return debugJdbcUrl;
    }

    /**
     * Closes database connections to clean up resources.
     * Called automatically when used in a try-with-resources block.
     * @throws Exception if closing connections fails.
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
