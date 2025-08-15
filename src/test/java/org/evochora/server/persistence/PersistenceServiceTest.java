package org.evochora.server.persistence;

import org.evochora.server.contracts.CellState;
import org.evochora.server.contracts.OrganismState;
import org.evochora.server.contracts.WorldStateMessage;
import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.queue.ITickMessageQueue;
import org.evochora.server.setup.Config;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PersistenceServiceTest {

    @Test
    void writesWorldStateRows() throws Exception {
        Config cfg = new Config();
        ITickMessageQueue q = new InMemoryTickQueue(cfg);
        PersistenceService persist = new PersistenceService(q, cfg);
        persist.start();

        var org = new OrganismState(
                1, "progA", null, 0L, 123L,
                List.of(1,2), List.of(0,1), List.of(1,0),
                5, 0, new int[]{0,1,2}, new int[]{3,4}, List.of(9,8), List.of(7)
        );
        var cell = new CellState(List.of(1,2), 2, 42, 1);
        var wsm = new WorldStateMessage(10L, 999L, List.of(org), List.of(cell));

        q.put(wsm);

        // Give consumer a moment
        Thread.sleep(100);

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


