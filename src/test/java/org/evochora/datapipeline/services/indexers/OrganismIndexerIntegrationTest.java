package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.*;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareMetadataWriter;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;
import org.evochora.datapipeline.resources.database.H2Database;
import org.evochora.datapipeline.resources.storage.FileSystemStorageResource;
import org.evochora.datapipeline.resources.topics.H2TopicResource;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test for OrganismIndexer end-to-end (without HTTP layer).
 * <p>
 * Covers:
 * <ul>
 *   <li>Metadata indexing prerequisite</li>
 *   <li>TickData batch written to storage + topic notification</li>
 *   <li>OrganismIndexer consuming from topic and writing to H2 database</li>
 *   <li>Idempotency on redelivery</li>
 * </ul>
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class OrganismIndexerIntegrationTest {

    private H2Database testDatabase;
    private FileSystemStorageResource testStorage;
    private H2TopicResource testBatchTopic;
    private OrganismIndexer<?> indexer;
    private Path tempStorageDir;

    @BeforeAll
    static void initInstructionSet() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() throws IOException {
        tempStorageDir = Files.createTempDirectory("evochora-test-organism-indexer-");
        Config storageConfig = ConfigFactory.parseString(
                "rootDirectory = \"" + tempStorageDir.toAbsolutePath().toString().replace("\\", "/") + "\""
        );
        testStorage = new FileSystemStorageResource("test-storage", storageConfig);

        String dbUrl = "jdbc:h2:mem:test-organism-indexer-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        Config dbConfig = ConfigFactory.parseString(
                "jdbcUrl = \"" + dbUrl + "\"\n" +
                "username = \"sa\"\n" +
                "password = \"\"\n" +
                "maxPoolSize = 10\n" +
                "organismRuntimeStateCompression { compression { enabled = true, codec = \"zstd\", level = 3 } }\n"
        );
        testDatabase = new H2Database("test-db", dbConfig);

        String topicJdbcUrl = "jdbc:h2:mem:test-batch-topic-org-" + UUID.randomUUID();
        Config topicConfig = ConfigFactory.parseString(
                "jdbcUrl = \"" + topicJdbcUrl + "\"\n" +
                "username = \"sa\"\n" +
                "password = \"\"\n" +
                "claimTimeout = 300"
        );
        testBatchTopic = new H2TopicResource("batch-topic", topicConfig);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (indexer != null) {
            indexer.stop();
        }
        if (testBatchTopic != null) {
            testBatchTopic.close();
        }
        if (testDatabase != null) {
            testDatabase.close();
        }
        // FileSystemStorageResource does not implement AutoCloseable; temp dir is cleaned below.
        if (tempStorageDir != null && Files.exists(tempStorageDir)) {
            Files.walk(tempStorageDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(java.io.File::delete);
        }
    }

    @Test
    void organismIndexer_endToEndAndIdempotent() throws Exception {
        String runId = "test-run-" + UUID.randomUUID();
        SimulationMetadata metadata = createMetadata(runId);

        indexMetadata(runId, metadata);

        // two ticks with same organism to test per-tick rows
        OrganismState organism = buildOrganismState(1);
        TickData tick1 = TickData.newBuilder()
                .setTickNumber(1L)
                .setSimulationRunId(runId)
                .addOrganisms(organism)
                .build();
        TickData tick2 = TickData.newBuilder()
                .setTickNumber(2L)
                .setSimulationRunId(runId)
                .addOrganisms(organism)
                .build();

        // write batch and notify once
        writeBatchAndNotify(runId, List.of(tick1, tick2));

        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            topicPollTimeoutMs = 2000
            insertBatchSize = 10
            flushTimeoutMs = 1000
            """.formatted(runId));

        indexer = createOrganismIndexer("organism-indexer", config);
        indexer.start();

        await().atMost(10, TimeUnit.SECONDS)
                .until(() -> indexer.getMetrics().get("ticks_processed").longValue() >= 2);

        // Redelivery: send same batch again to test MERGE idempotency
        writeBatchAndNotify(runId, List.of(tick1, tick2));

        await().atMost(10, TimeUnit.SECONDS)
                .until(() -> indexer.getMetrics().get("batches_processed").longValue() >= 2);

        // Verify DB contents
        try (var conn = getConnectionForRun(runId)) {
            try (var rs = conn.createStatement().executeQuery("SELECT COUNT(*) AS cnt FROM organisms")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("cnt")).isEqualTo(1);
            }
            try (var rs = conn.createStatement().executeQuery("SELECT COUNT(*) AS cnt FROM organism_states")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("cnt")).isEqualTo(2); // two ticks, same organism
            }
        }
    }

    private OrganismIndexer<?> createOrganismIndexer(String name, Config config) {
        ResourceContext dbMetaContext = new ResourceContext(
                name, "metadata", "db-meta-read", "test-db", Map.of());
        IResource wrappedDbMeta = testDatabase.getWrappedResource(dbMetaContext);

        ResourceContext dbOrgContext = new ResourceContext(
                name, "database", "db-organism-write", "test-db", Map.of());
        IResource wrappedDbOrg = testDatabase.getWrappedResource(dbOrgContext);

        ResourceContext storageContext = new ResourceContext(
                name, "storage", "storage-read", "test-storage", Map.of());
        IResource wrappedStorage = testStorage.getWrappedResource(storageContext);

        ResourceContext topicContext = new ResourceContext(
                name, "topic", "topic-read", "batch-topic",
                Map.of("consumerGroup", "test-organism-" + UUID.randomUUID()));
        IResource wrappedTopic = testBatchTopic.getWrappedResource(topicContext);

        Map<String, List<IResource>> resources = Map.of(
                "database", List.of(wrappedDbOrg),
                "storage", List.of(wrappedStorage),
                "topic", List.of(wrappedTopic),
                "metadata", List.of(wrappedDbMeta)
        );

        return new OrganismIndexer<>(name, config, resources);
    }

    private SimulationMetadata createMetadata(String runId) {
        EnvironmentConfig.Builder envBuilder = EnvironmentConfig.newBuilder()
                .setDimensions(2)
                .addShape(10)
                .addToroidal(false)
                .addShape(10)
                .addToroidal(false);

        return SimulationMetadata.newBuilder()
                .setSimulationRunId(runId)
                .setEnvironment(envBuilder.build())
                .setStartTimeMs(Instant.now().toEpochMilli())
                .setInitialSeed(42L)
                .setSamplingInterval(1)
                .build();
    }

    private void indexMetadata(String runId, SimulationMetadata metadata) throws Exception {
        ResourceContext metaWriteContext = new ResourceContext("test", "metadata-port", "db-meta-write", "test-db", Map.of());
        IResource wrapped = testDatabase.getWrappedResource(metaWriteContext);
        IResourceSchemaAwareMetadataWriter writer = (IResourceSchemaAwareMetadataWriter) wrapped;
        writer.setSimulationRun(runId);
        writer.insertMetadata(metadata);
        ((AutoCloseable) writer).close();
    }

    private void writeBatchAndNotify(String runId, List<TickData> ticks) throws Exception {
        ResourceContext storageWriteContext = new ResourceContext(
                "test", "storage-port", "storage-write", "test-storage", Map.of("simulationRunId", runId));
        IResource storageWriter = testStorage.getWrappedResource(storageWriteContext);
        IBatchStorageWrite batchWriter = (IBatchStorageWrite) storageWriter;

        long firstTick = ticks.get(0).getTickNumber();
        long lastTick = ticks.get(ticks.size() - 1).getTickNumber();
        StoragePath batchPath = batchWriter.writeBatch(ticks, firstTick, lastTick);

        ResourceContext topicWriteContext = new ResourceContext("test", "topic-port", "topic-write", "batch-topic", Map.of());
        IResource topicWriterResource = testBatchTopic.getWrappedResource(topicWriteContext);
        @SuppressWarnings("unchecked")
        ITopicWriter<BatchInfo> topicWriter = (ITopicWriter<BatchInfo>) topicWriterResource;

        topicWriter.setSimulationRun(runId);

        BatchInfo batchInfo = BatchInfo.newBuilder()
                .setSimulationRunId(runId)
                .setStoragePath(batchPath.asString())
                .setTickStart(firstTick)
                .setTickEnd(lastTick)
                .setWrittenAtMs(Instant.now().toEpochMilli())
                .build();
        topicWriter.send(batchInfo);
        topicWriter.close();
    }

    private Connection getConnectionForRun(String runId) throws SQLException {
        java.lang.reflect.Field dataSourceField;
        try {
            dataSourceField = H2Database.class.getDeclaredField("dataSource");
            dataSourceField.setAccessible(true);
            com.zaxxer.hikari.HikariDataSource dataSource =
                    (com.zaxxer.hikari.HikariDataSource) dataSourceField.get(testDatabase);
            Connection conn = dataSource.getConnection();
            org.evochora.datapipeline.utils.H2SchemaUtil.setSchema(conn, runId);
            return conn;
        } catch (ReflectiveOperationException e) {
            throw new SQLException("Failed to access H2 dataSource", e);
        }
    }

    private OrganismState buildOrganismState(int id) {
        Vector ip = Vector.newBuilder().addComponents(1).addComponents(2).build();
        Vector dv = Vector.newBuilder().addComponents(0).addComponents(1).build();
        Vector initialPos = Vector.newBuilder().addComponents(0).addComponents(0).build();
        Vector ipBeforeFetch = Vector.newBuilder().addComponents(1).addComponents(2).build();
        Vector dvBeforeFetch = Vector.newBuilder().addComponents(0).addComponents(1).build();

        // SETI %DR0, DATA:100 instruction
        int setiOpcode = Instruction.getInstructionIdByName("SETI") | org.evochora.runtime.Config.TYPE_CODE;
        int regArg = new Molecule(org.evochora.runtime.Config.TYPE_DATA, 0).toInt();
        int immArg = new Molecule(org.evochora.runtime.Config.TYPE_DATA, 100).toInt();

        return OrganismState.newBuilder()
                .setOrganismId(id)
                .setBirthTick(0)
                .setProgramId("prog-" + id)
                .setInitialPosition(initialPos)
                .setEnergy(100)
                .setIp(ip)
                .setDv(dv)
                .addDataPointers(Vector.newBuilder().addComponents(5).addComponents(5).build())
                .setActiveDpIndex(0)
                .addDataRegisters(RegisterValue.newBuilder().setScalar(7).build())
                .addLocationRegisters(Vector.newBuilder().addComponents(2).addComponents(3).build())
                .addDataStack(RegisterValue.newBuilder().setScalar(9).build())
                .addLocationStack(Vector.newBuilder().addComponents(4).addComponents(4).build())
                .addCallStack(ProcFrame.newBuilder()
                        .setProcName("main")
                        .setAbsoluteReturnIp(Vector.newBuilder().addComponents(10).addComponents(10).build())
                        .build())
                .setInstructionFailed(true)
                .setFailureReason("integration-failure")
                .addFailureCallStack(ProcFrame.newBuilder()
                        .setProcName("fail")
                        .setAbsoluteReturnIp(Vector.newBuilder().addComponents(11).addComponents(11).build())
                        .build())
                // Instruction execution data
                .setInstructionOpcodeId(setiOpcode)
                .addInstructionRawArguments(regArg)
                .addInstructionRawArguments(immArg)
                .setInstructionEnergyCost(5)
                .setIpBeforeFetch(ipBeforeFetch)
                .setDvBeforeFetch(dvBeforeFetch)
                .build();
    }
}


