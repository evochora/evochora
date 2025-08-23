package org.evochora.server.persistence;

import org.evochora.server.contracts.CellState;
import org.evochora.server.contracts.OrganismState;
import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.queue.ITickMessageQueue;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class PersistenceServiceTest {

    @Test
    void writesWorldStateRows() throws Exception {
        ITickMessageQueue q = new InMemoryTickQueue();
        PersistenceService persist = new PersistenceService(q, false, "jdbc:sqlite:file:psvcTest?mode=memory&cache=shared");
        persist.start();

        var cell = new org.evochora.server.contracts.CellState(List.of(1,2), 2, 42, 1);
        var pts = org.evochora.server.contracts.PreparedTickState.of(
            "debug", 
            10L, 
            new org.evochora.server.contracts.PreparedTickState.WorldMeta(new int[]{20, 20}),
            new org.evochora.server.contracts.PreparedTickState.WorldState(
                List.of(new org.evochora.server.contracts.PreparedTickState.Cell(List.of(1,2), "DATA", 42, 1, null)),
                List.of(new org.evochora.server.contracts.PreparedTickState.OrganismBasic(
                    1, "progA", List.of(1,2), 123L, List.of(List.of(0,1)), List.of(1,0)
                ))
            ),
            Map.of("1", new org.evochora.server.contracts.PreparedTickState.OrganismDetails(
                new org.evochora.server.contracts.PreparedTickState.BasicInfo(1, "progA", null, 0L, 123L, List.of(1,2), List.of(1,0)),
                new org.evochora.server.contracts.PreparedTickState.NextInstruction("", null, null),
                new org.evochora.server.contracts.PreparedTickState.InternalState(
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
                ),
                null
            ))
        );

        q.put(pts);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (persist.getLastPersistedTick() < 10L) {
                Thread.sleep(50);
            }
        }, "PersistenceService did not process the tick in time.");

        try (Connection c = DriverManager.getConnection(persist.getJdbcUrl())) {
            // Check that the PreparedTickState was stored in the prepared_ticks table
            ResultSet rsTick = c.createStatement().executeQuery("SELECT COUNT(*) FROM prepared_ticks WHERE tick_number=10");
            assertThat(rsTick.next()).isTrue();
            assertThat(rsTick.getInt(1)).isEqualTo(1);

            // Check the actual JSON content to verify organism and cell data
            ResultSet rsData = c.createStatement().executeQuery("SELECT tick_data_json FROM prepared_ticks WHERE tick_number=10");
            assertThat(rsData.next()).isTrue();
            String json = rsData.getString(1);
            
            // Verify the JSON contains the expected organism and cell information
            assertThat(json).contains("\"programId\":\"progA\"");
            assertThat(json).contains("\"id\":1");
            assertThat(json).contains("\"position\":[1,2]");
            assertThat(json).contains("\"type\":\"DATA\"");
            assertThat(json).contains("\"value\":42");
            assertThat(json).contains("\"ownerId\":1");
        }

        persist.shutdown();
    }
}