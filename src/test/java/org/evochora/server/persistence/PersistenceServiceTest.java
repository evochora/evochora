package org.evochora.server.persistence;

import org.evochora.server.contracts.CellState;
import org.evochora.server.contracts.OrganismState;
import org.evochora.server.contracts.WorldStateMessage;
import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.queue.ITickMessageQueue;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class PersistenceServiceTest {

    @Test
    void writesWorldStateRows() throws Exception {
        ITickMessageQueue q = new InMemoryTickQueue();
        PersistenceService persist = new PersistenceService(q, false);
        persist.start();

        var org = new OrganismState(
                1, "progA", null, 0L, 123L,
                List.of(1,2), List.of(0,1), List.of(1,0),
                5, 0,
                List.of("DATA:1", "DATA:2"),
                List.of("DATA:3"),
                List.of("DATA:9", "DATA:8"),
                List.of("MY_PROC"),
                List.of(),
                "{}"
        );
        var cell = new CellState(List.of(1,2), 2, 42, 1);
        var wsm = new WorldStateMessage(10L, 999L, List.of(org), List.of(cell));

        q.put(wsm);

        // KORREKTUR: Ersetze Thread.sleep durch eine robuste Warte-Schleife.
        // Wir warten bis zu 2 Sekunden darauf, dass der PersistenceService den Tick 10 verarbeitet hat.
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (persist.getLastPersistedTick() < 10L) {
                Thread.sleep(50); // Kurz warten und erneut prüfen
            }
        }, "PersistenceService hat den Tick nicht innerhalb des Zeitlimits verarbeitet.");


        var db = persist.getDbFilePath();
        assertThat(Files.exists(db)).isTrue();

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath())) {
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
