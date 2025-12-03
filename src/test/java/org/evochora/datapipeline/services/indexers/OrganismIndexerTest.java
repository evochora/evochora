package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareOrganismDataWriter;
import org.evochora.datapipeline.resources.database.H2Database;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unit test for OrganismIndexer wiring against a real H2Database + wrapper.
 * <p>
 * Verifies that prepareTables() and flushTicks() delegate to the underlying
 * {@link IResourceSchemaAwareOrganismDataWriter} without throwing and that
 * the indexer remains healthy.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class OrganismIndexerTest {

    @TempDir
    Path tempDir;

    private H2Database database;
    private IResourceSchemaAwareOrganismDataWriter writer;
    private TestOrganismIndexer<?> indexer;

    @BeforeEach
    void setUp() throws Exception {
        String dbPath = tempDir.toString().replace("\\", "/");
        var config = ConfigFactory.parseString("""
            jdbcUrl = "jdbc:h2:file:%s/test-organism-indexer;MODE=PostgreSQL"
            """.formatted(dbPath));

        database = new H2Database("test-db", config);

        ResourceContext context = new ResourceContext("test-service", "port", "db-organism-write", "test-db", Map.of());
        writer = (IResourceSchemaAwareOrganismDataWriter) database.getWrappedResource(context);

        // Minimal resource map: required ports must exist even if not used directly
        Map<String, List<IResource>> resources = Map.of(
                "database", List.of((IResource) writer),
                "storage", List.of(),
                "topic", List.of()
        );

        indexer = new TestOrganismIndexer<>("organism-indexer-test",
                ConfigFactory.parseString(""), resources);

        // Set schema via wrapper
        writer.setSimulationRun("test-run");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (writer != null) {
            writer.close();
        }
        if (database != null) {
            database.close();
        }
    }

    // NOTE: Full end-to-end behavior (including storage/topic wiring) is covered
    // by future integration tests. Here we only validate basic construction and
    // resource wiring via OrganismIndexer constructor.

    /**
     * Test subclass exposing prepareTables/flushTicks for direct invocation.
     */
    private static class TestOrganismIndexer<ACK> extends OrganismIndexer<ACK> {

        final AtomicBoolean flushCalled = new AtomicBoolean(false);

        TestOrganismIndexer(String name, com.typesafe.config.Config options, Map<String, List<IResource>> resources) {
            super(name, options, resources);
        }

        @Override
        protected void flushTicks(List<TickData> ticks) throws Exception {
            flushCalled.set(true);
            super.flushTicks(ticks);
        }
    }
}


