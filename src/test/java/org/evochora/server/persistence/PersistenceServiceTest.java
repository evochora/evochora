package org.evochora.server.persistence;

import org.evochora.server.contracts.CellState;
import org.evochora.server.contracts.OrganismState;
import org.evochora.server.contracts.WorldStateMessage;
import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.queue.ITickMessageQueue;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class PersistenceServiceTest {

    @Test
    void writesWorldStateRows() throws Exception {
        ITickMessageQueue q = new InMemoryTickQueue();
        PersistenceService persist = new PersistenceService(q, false, "jdbc:sqlite:file:psvcTest?mode=memory&cache=shared");
        persist.start();

        var org = new OrganismState(
                1, "progA", null, 0L, 123L,
                List.of(1,2), List.of(List.of(0,1)), List.of(1,0), // CORRECTED: Wrapped dp in a list
                5, 0,
                List.of("DATA:1", "DATA:2"),
                List.of("DATA:3"),
                List.of("DATA:9", "DATA:8"),
                List.of("MY_PROC"),
                java.util.List.<String>of(),
                java.util.List.<String>of(),
                "{}",
                Collections.emptyList(), // CORRECTED: Added empty list for locationRegisters
                Collections.emptyList()  // CORRECTED: Added empty list for locationStack
        );
        var cell = new CellState(List.of(1,2), 2, 42, 1);
        var wsm = new WorldStateMessage(10L, 999L, List.of(org), List.of(cell));

        q.put(wsm);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (persist.getLastPersistedTick() < 10L) {
                Thread.sleep(50);
            }
        }, "PersistenceService did not process the tick in time.");

        try (Connection c = DriverManager.getConnection(persist.getJdbcUrl())) {
            ResultSet rsTick = c.createStatement().executeQuery("select count(*) from ticks where tickNumber=10");
            assertThat(rsTick.next()).isTrue();
            assertThat(rsTick.getInt(1)).isEqualTo(1);

            ResultSet rsOrg = c.createStatement().executeQuery("select count(*) from organism_states where tickNumber=10 and organismId=1 and programId='progA'");
            assertThat(rsOrg.next()).isTrue();
            assertThat(rsOrg.getInt(1)).isEqualTo(1);

            ResultSet rsCell = c.createStatement().executeQuery("select count(*) from cell_states where tickNumber=10");
            assertThat(rsCell.next()).isTrue();
            assertThat(rsCell.getInt(1)).isEqualTo(1);
        }

        persist.shutdown();
    }
}