package org.evochora.datapipeline.resources.database;

import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for OrganismDataWriterWrapper.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class OrganismDataWriterWrapperTest {

    @TempDir
    Path tempDir;

    private H2Database database;
    private OrganismDataWriterWrapper wrapper;

    @BeforeEach
    void setUp() throws Exception {
        String dbPath = tempDir.toString().replace("\\", "/");
        var config = ConfigFactory.parseString("""
            jdbcUrl = "jdbc:h2:file:%s/test-organism-wrapper;MODE=PostgreSQL"
            """.formatted(dbPath));

        database = new H2Database("test-db", config);

        ResourceContext context = new ResourceContext("test-service", "port", "db-organism-write", "test-db", Map.of());
        wrapper = (OrganismDataWriterWrapper) database.getWrappedResource(context);

        wrapper.setSimulationRun("test-run");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (wrapper != null) {
            wrapper.close();
        }
        if (database != null) {
            database.close();
        }
    }

    @Test
    void testCreateOrganismTables_Explicit() throws SQLException {
        // When
        wrapper.createOrganismTables();

        // Then
        assertThat(wrapper.isHealthy()).isTrue();
    }

    @Test
    void testWriteOrganismStates_EmptyList() {
        // When
        wrapper.writeOrganismStates(List.of());

        // Then
        assertThat(wrapper.isHealthy()).isTrue();

        Map<String, Number> metrics = wrapper.getMetrics();
        assertThat(metrics.get("organisms_written").longValue()).isEqualTo(0);
        assertThat(metrics.get("batches_written").longValue()).isEqualTo(0);
    }

    @Test
    void testCollectMetrics_AllFieldsPresent() {
        Map<String, Number> metrics = wrapper.getMetrics();

        assertThat(metrics).containsKeys(
                "connection_cached",
                "organisms_written",
                "batches_written",
                "write_errors",
                "organisms_per_second",
                "batches_per_second",
                "write_latency_p50_ms",
                "write_latency_p95_ms",
                "write_latency_p99_ms",
                "write_latency_avg_ms"
        );
    }

    // NOTE: Wrapper-specific error handling is intentionally not duplicated here;
    // end-to-end failure handling for writeOrganismStates is covered by dedicated
    // H2Database and OrganismIndexer tests.
}


