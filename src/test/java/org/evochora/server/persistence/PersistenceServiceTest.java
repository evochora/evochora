package org.evochora.server.persistence;

import org.evochora.server.contracts.raw.RawCellState;
import org.evochora.server.contracts.raw.RawTickState;
import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.queue.ITickMessageQueue;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.server.config.SimulationConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contains integration tests for the {@link PersistenceService}.
 * These tests verify the core functionality of the service, ensuring it can
 * consume tick data from a queue and correctly persist it to a database.
 */
class PersistenceServiceTest {

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
                System.out.println("Timeout waiting for: " + description);
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
     * Verifies that the PersistenceService correctly consumes a RawTickState from the queue,
     * serializes it to JSON, and writes it as a new row in the target database.
     * <p>
     * This is an integration test as it involves a live, threaded service and a
     * real (in-memory) database connection.
     *
     * @throws Exception if thread or database operations fail.
     */
    @Test
    @Tag("unit")
    void writesRawTickStateRows() throws Exception {
        ITickMessageQueue q = new InMemoryTickQueue(1000);
        // Use the available constructor with worldShape and batchSize
        SimulationConfiguration.PersistenceServiceConfig config = new SimulationConfiguration.PersistenceServiceConfig();
        config.jdbcUrl = "jdbc:sqlite:file:memdb_persistence?mode=memory&cache=shared";
        config.batchSize = 1;
        PersistenceService persist = new PersistenceService(q, new EnvironmentProperties(new int[]{10, 10}, true), config);
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
        
        // Wait for shutdown to complete
        Thread.sleep(1000);
        assertThat(persist.isRunning()).isFalse();
    }

}