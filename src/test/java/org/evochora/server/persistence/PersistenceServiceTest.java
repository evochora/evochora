package org.evochora.server.persistence;

import org.evochora.server.contracts.raw.RawCellState;
import org.evochora.server.contracts.raw.RawTickState;
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
    void writesRawTickStateRows() throws Exception {
        ITickMessageQueue q = new InMemoryTickQueue();
        PersistenceService persist = new PersistenceService(q, "jdbc:sqlite:file:psvcTest?mode=memory&cache=shared");
        persist.start();

        var rawCell = new RawCellState(new int[]{1, 2}, 42, 1);
        var rawTick = new RawTickState(10L, Collections.emptyList(), List.of(rawCell));

        q.put(rawTick);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (persist.getLastPersistedTick() < 10L) {
                Thread.sleep(50);
            }
        }, "PersistenceService did not process the tick in time.");

        try (Connection c = DriverManager.getConnection(persist.getJdbcUrl())) {
            ResultSet rsTick = c.createStatement().executeQuery("SELECT COUNT(*) FROM raw_ticks WHERE tick_number=10");
            assertThat(rsTick.next()).isTrue();
            assertThat(rsTick.getInt(1)).isEqualTo(1);

            ResultSet rsData = c.createStatement().executeQuery("SELECT tick_data_json FROM raw_ticks WHERE tick_number=10");
            assertThat(rsData.next()).isTrue();
            String json = rsData.getString(1);

            assertThat(json).contains("\"tickNumber\":10");
            assertThat(json).contains("\"pos\":[1,2]");
            assertThat(json).contains("\"molecule\":42");
            assertThat(json).contains("\"ownerId\":1");
        }

        persist.shutdown();
    }
}