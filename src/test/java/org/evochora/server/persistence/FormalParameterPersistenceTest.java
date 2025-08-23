package org.evochora.server.persistence;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.server.engine.WorldStateAdapter;
import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.queue.ITickMessageQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FormalParameterPersistenceTest {

    private PersistenceService persist;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @AfterEach
    void tearDown() {
        if (persist != null && persist.isRunning()) persist.shutdown();
    }

    @Test
    void formalParameters_persist_as_resolved_DRs_not_FPRs() throws Exception {
        // Prepare in-memory simulation
        Environment env = new Environment(new int[]{20, 20}, true);
        Simulation sim = new Simulation(env, false);

        // Minimal ProgramArtifact with parameters for MY_PROC
        Map<String, List<String>> procParams = new HashMap<>();
        procParams.put("MY_PROC", List.of("REG1", "REG2"));
        ProgramArtifact artifact = new ProgramArtifact(
                "p1",
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                procParams
        );
        sim.setProgramArtifacts(Map.of("p1", artifact));

        // Create organism and push a call frame with FPR bindings -> DR0, DR1
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        org.setProgramId("p1");
        org.setDr(0, new Molecule(Config.TYPE_DATA, 3).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 6).toInt());

        java.util.Map<Integer, Integer> fprBindings = new java.util.HashMap<>();
        fprBindings.put(Instruction.FPR_BASE + 0, 0);
        fprBindings.put(Instruction.FPR_BASE + 1, 1);
        Organism.ProcFrame frame = new Organism.ProcFrame(
                "MY_PROC",
                new int[]{0,0},
                new Object[Config.NUM_PROC_REGISTERS],
                new Object[Config.NUM_FORMAL_PARAM_REGISTERS],
                fprBindings
        );
        org.getCallStack().push(frame);
        sim.addOrganism(org);

        // Build world state message via adapter (this is what gets persisted)
        org.evochora.server.contracts.PreparedTickState pts = WorldStateAdapter.toPreparedState(sim);

        // Prepare temp sqlite file
        Path tmp = Files.createTempFile("evochora_fp_test", ".sqlite");
        String jdbcUrl = "jdbc:sqlite:" + tmp.toAbsolutePath();

        // Start persistence and enqueue message
        ITickMessageQueue q = new InMemoryTickQueue();
        persist = new PersistenceService(q, false, jdbcUrl);
        persist.start();
        q.put(pts);

        // Give persistence time to write and process
        Thread.sleep(500);

        // Read back formalParameters from DB
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT tick_data_json FROM prepared_ticks WHERE tick_number=0 LIMIT 1");
            assertThat(rs.next()).as("Should find at least one row in prepared_ticks table").isTrue();
            String json = rs.getString(1);
            // Check that the JSON contains the expected formal parameter information
            assertThat(json).contains("REG1[%DR0]");
            assertThat(json).contains("REG2[%DR1]");
            assertThat(json).doesNotContain("%FPR0");
            assertThat(json).doesNotContain("%FPR1");
        }
    }
}


