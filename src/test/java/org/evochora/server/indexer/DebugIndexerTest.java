package org.evochora.server.indexer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.isa.Instruction;
import org.evochora.server.contracts.debug.PreparedTickState;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.contracts.raw.RawTickState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DebugIndexerTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void init() {
        Instruction.init();
    }


    @Test
    @Disabled("Temporarily disabled due to Jackson serialization issue with int[] map keys in ProgramArtifact. " +
              "The DebugIndexer requires ProgramArtifact data to transform raw ticks, but Jackson cannot serialize " +
              "maps with int[] keys. This needs to be fixed with custom serializers or data structure changes.")
    void indexer_readsRawDb_and_writesPreparedDb() throws Exception {
        // 1. Arrange: Erstelle eine temporäre Roh-Datenbank
        Path rawDbPath = tempDir.resolve("test_run_raw.sqlite");
        String rawJdbcUrl = "jdbc:sqlite:" + rawDbPath.toAbsolutePath();
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
        RawTickState rawTick = new RawTickState(0L, List.of(rawOrganism), Collections.emptyList());

        // Befülle die Roh-Datenbank
        try (Connection conn = DriverManager.getConnection(rawJdbcUrl); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE program_artifacts (program_id TEXT PRIMARY KEY, artifact_json TEXT)");
            st.execute("CREATE TABLE raw_ticks (tick_number INTEGER PRIMARY KEY, tick_data_json TEXT)");
            st.execute("CREATE TABLE simulation_metadata (key TEXT PRIMARY KEY, value TEXT)");
            // TODO: Fix Jackson serialization issue with int[] map keys in ProgramArtifact
            // try (PreparedStatement ps = conn.prepareStatement("INSERT INTO program_artifacts VALUES (?,?)")) {
            //     ps.setString(1, artifact.programId());
            //     ps.setString(2, mapper.writeValueAsString(artifact));
            //     ps.executeUpdate();
            // }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO raw_ticks VALUES (?,?)")) {
                ps.setLong(1, 0);
                ps.setString(2, mapper.writeValueAsString(rawTick));
                ps.executeUpdate();
            }
            // Add worldShape metadata that DebugIndexer expects
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO simulation_metadata VALUES (?,?)")) {
                ps.setString(1, "worldShape");
                ps.setString(2, mapper.writeValueAsString(new int[]{10, 10}));
                ps.executeUpdate();
            }
        }

        // 2. Act: Starte den Indexer und lass ihn einen Tick verarbeiten
        DebugIndexer indexer = new DebugIndexer(rawDbPath.toAbsolutePath().toString());
        indexer.start();
        Thread.sleep(2000); // Gib dem Indexer Zeit zu arbeiten
        indexer.shutdown();

        // 3. Assert: Überprüfe die Debug-Datenbank
        Path debugDbPath = tempDir.resolve("test_run_debug.sqlite");
        String debugJdbcUrl = "jdbc:sqlite:" + debugDbPath.toAbsolutePath();

        assertThat(debugDbPath).exists();
        try (Connection conn = DriverManager.getConnection(debugJdbcUrl);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT tick_data_json FROM prepared_ticks WHERE tick_number = 0")) {

            assertThat(rs.next()).isTrue();
            String preparedJson = rs.getString(1);
            PreparedTickState preparedTick = mapper.readValue(preparedJson, PreparedTickState.class);

            assertThat(preparedTick.tickNumber()).isEqualTo(0);
            assertThat(preparedTick.organismDetails()).containsKey("1");
            // Weitere, detailliertere Assertions können hier hinzugefügt werden,
            // sobald die Transformationslogik vollständig ist.
        }
    }
}