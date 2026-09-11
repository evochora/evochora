package org.evochora.datapipeline.resources.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;

import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.ProcFrame;
import org.evochora.datapipeline.api.contracts.ProgramArtifact;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.StoredMutationEvent;
import org.evochora.datapipeline.api.contracts.StoredMutationEvents;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickDetails;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.runtime.worldgen.LabelRewritePlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import com.typesafe.config.ConfigFactory;

/**
 * Tests procedure name resolution in H2DatabaseReader.
 * <p>
 * Call frames persist a label hash, not a name. The name is recovered on read from the label map of
 * the program the organism descends from, after the organism's own label namespace has been taken
 * out of the hash. These tests cover the cases that arise: a hash the map knows, a hash it does
 * not, a run whose metadata carries no map at all, and a descendant whose ancestry rewrote the
 * labels it calls through.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class H2DatabaseReaderProcedureNameResolutionTest {

    private static final String PROGRAM_ID = "prog-1";
    private static final int ORGANISM_ID = 1;
    private static final int TICK = 0;

    private static final int KNOWN_HASH = 4711;
    private static final String KNOWN_NAME = "MAIN_LOOP";
    private static final int UNKNOWN_HASH = 9999;

    private static final int CHILD_ID = 2;
    private static final int GRANDCHILD_ID = 3;
    private static final int CHILD_MASK = 0b101_0110_1010_1100_110;
    private static final int GRANDCHILD_MASK = 0b011_1001_0011_0101_001;

    @TempDir
    Path tempChunkDir;

    private H2Database database;
    private IDatabaseReaderProvider provider;
    private String runId;

    @BeforeEach
    void setUp() {
        String dbUrl = "jdbc:h2:mem:test-procname-resolution-" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        com.typesafe.config.Config dbConfig = ConfigFactory.parseString(
            "jdbcUrl = \"" + dbUrl + "\"\n" +
            "username = \"sa\"\n" +
            "password = \"\"\n" +
            "maxPoolSize = 5\n" +
            "h2EnvironmentStrategy {\n" +
            "  className = \"org.evochora.datapipeline.resources.database.h2.RowPerChunkStrategy\"\n" +
            "  options { chunkDirectory = \"" + tempChunkDir.toString().replace("\\", "/") + "\" }\n" +
            "}\n"
        );
        database = new H2Database("test-db", dbConfig);
        provider = database;
        runId = "test-run-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void resolvesNameOfAKnownLabelHash() throws Exception {
        setupDatabase(true);
        writeOrganismWithCallStack(KNOWN_HASH);

        try (IDatabaseReader reader = provider.createReader(runId)) {
            OrganismTickDetails details = reader.readOrganismDetails(TICK, ORGANISM_ID);
            assertThat(details.state.callStack).hasSize(1);
            assertThat(details.state.callStack.get(0).procName).isEqualTo(KNOWN_NAME);
        }
    }

    /**
     * An organism inherits the program ID of its ancestor while its code mutates, so a call may
     * target a hash the original program never held. Reporting no name is the truthful result, and
     * a placeholder would be wrong: callers tell named from unnamed frames by emptiness.
     */
    @Test
    void yieldsEmptyNameForAnUnknownLabelHash() throws Exception {
        setupDatabase(true);
        writeOrganismWithCallStack(UNKNOWN_HASH);

        try (IDatabaseReader reader = provider.createReader(runId)) {
            OrganismTickDetails details = reader.readOrganismDetails(TICK, ORGANISM_ID);
            assertThat(details.state.callStack).hasSize(1);
            assertThat(details.state.callStack.get(0).procName).isEmpty();
        }
    }

    /** A run whose metadata holds no label map resolves every frame to an empty name. */
    @Test
    void yieldsEmptyNamesWhenTheRunHasNoLabelMap() throws Exception {
        setupDatabase(false);
        writeOrganismWithCallStack(KNOWN_HASH);

        try (IDatabaseReader reader = provider.createReader(runId)) {
            OrganismTickDetails details = reader.readOrganismDetails(TICK, ORGANISM_ID);
            assertThat(details.state.callStack).hasSize(1);
            assertThat(details.state.callStack.get(0).procName).isEmpty();
        }
    }

    /**
     * Every newborn's labels and label references are XOR-masked into a namespace of its own, and
     * every descendant's again at its own birth. A frame of the third generation therefore holds a
     * hash that is the compiled value XORed with both masks, and only their composition recovers
     * the name the artifact knows. A chain of two would still pass if only the organism's own mask
     * were applied, which is the mistake worth catching.
     */
    @Test
    void resolvesNameThroughTheLabelMasksOfAWholeAncestry() throws Exception {
        setupDatabase(true);
        writeAncestryWithMaskedCallStack(KNOWN_HASH ^ CHILD_MASK ^ GRANDCHILD_MASK);

        try (IDatabaseReader reader = provider.createReader(runId)) {
            OrganismTickDetails grandchild = reader.readOrganismDetails(TICK, GRANDCHILD_ID);
            assertThat(grandchild.staticInfo.labelNamespaceMask).isEqualTo(CHILD_MASK ^ GRANDCHILD_MASK);
            assertThat(grandchild.state.callStack).hasSize(1);
            assertThat(grandchild.state.callStack.get(0).procName).isEqualTo(KNOWN_NAME);

            // The founding organism of the same chain rewrote nothing and must stay untouched
            OrganismTickDetails founder = reader.readOrganismDetails(TICK, ORGANISM_ID);
            assertThat(founder.staticInfo.labelNamespaceMask).isZero();
            assertThat(founder.state.callStack.get(0).procName).isEqualTo(KNOWN_NAME);
        }
    }

    private void setupDatabase(boolean withLabelMap) throws Exception {
        Object connObj = database.acquireDedicatedConnection();
        try (Connection conn = (Connection) connObj) {
            String schemaName = "SIM_" + runId.toUpperCase().replaceAll("[^A-Z0-9_]", "_");
            conn.createStatement().execute("CREATE SCHEMA IF NOT EXISTS \"" + schemaName + "\"");
            conn.createStatement().execute("SET SCHEMA \"" + schemaName + "\"");

            conn.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS metadata (\"key\" VARCHAR PRIMARY KEY, \"value\" TEXT)");

            ProgramArtifact.Builder program = ProgramArtifact.newBuilder().setProgramId(PROGRAM_ID);
            if (withLabelMap) {
                program.putLabelValueToName(KNOWN_HASH, KNOWN_NAME);
            }

            SimulationMetadata metadata = SimulationMetadata.newBuilder()
                    .setSimulationRunId(runId)
                    .setResolvedConfigJson(TestMetadataHelper.builder()
                        .shape(10, 10)
                        .toroidal(false)
                        .samplingInterval(1)
                        .build())
                    .setStartTimeMs(System.currentTimeMillis())
                    .setInitialSeed(42L)
                    .addPrograms(program.build())
                    .build();
            String metadataJson =
                    org.evochora.datapipeline.utils.protobuf.ProtobufConverter.toJson(metadata);
            conn.createStatement().execute("INSERT INTO metadata (\"key\", \"value\") VALUES ('full_metadata', '"
                    + metadataJson.replace("'", "''") + "')");

            database.doCreateOrganismTables(conn);
            conn.commit();
        }
    }

    private void writeOrganismWithCallStack(int labelHash) throws Exception {
        Object connObj = database.acquireDedicatedConnection();
        try (Connection conn = (Connection) connObj) {
            org.evochora.datapipeline.utils.H2SchemaUtil.setSchema(conn, runId);

            ProcFrame frame = ProcFrame.newBuilder()
                    .setLabelHash(labelHash)
                    .setAbsoluteReturnIp(Vector.newBuilder().addComponents(3).addComponents(4).build())
                    .build();

            OrganismState organism = OrganismState.newBuilder()
                    .setOrganismId(ORGANISM_ID)
                    .setBirthTick(0)
                    .setProgramId(PROGRAM_ID)
                    .setInitialPosition(Vector.newBuilder().addComponents(0).addComponents(0).build())
                    .setEnergy(100)
                    .setIp(Vector.newBuilder().addComponents(1).addComponents(2).build())
                    .setDv(Vector.newBuilder().addComponents(0).addComponents(1).build())
                    .addDataPointers(Vector.newBuilder().addComponents(5).addComponents(5).build())
                    .setActiveDpIndex(0)
                    .addCallStack(frame)
                    .build();

            TickData tick = TickData.newBuilder()
                    .setTickNumber(TICK)
                    .setSimulationRunId(runId)
                    .addOrganisms(organism)
                    .build();

            database.doWriteOrganismTick(conn, tick, java.util.Map.of());
            database.doCommitOrganismWrites(conn);
        }
    }

    /**
     * Writes a founding organism, its child and its grandchild into one tick, each newborn with the
     * label mask its birth applied. The founder and the grandchild both carry a call stack, so that
     * one read covers the masked case and the unmasked one.
     *
     * @param grandchildLabelHash the hash the grandchild's frame carries
     */
    private void writeAncestryWithMaskedCallStack(int grandchildLabelHash) throws Exception {
        Object connObj = database.acquireDedicatedConnection();
        try (Connection conn = (Connection) connObj) {
            org.evochora.datapipeline.utils.H2SchemaUtil.setSchema(conn, runId);

            TickData tick = TickData.newBuilder()
                    .setTickNumber(TICK)
                    .setSimulationRunId(runId)
                    .addOrganisms(organism(ORGANISM_ID, null, KNOWN_HASH))
                    .addOrganisms(organism(CHILD_ID, ORGANISM_ID, null))
                    .addOrganisms(organism(GRANDCHILD_ID, CHILD_ID, grandchildLabelHash))
                    .build();

            database.doWriteOrganismTick(conn, tick, java.util.Map.of(
                    CHILD_ID, labelRewrite(CHILD_MASK),
                    GRANDCHILD_ID, labelRewrite(GRANDCHILD_MASK)));
            database.doCommitOrganismWrites(conn);
        }
    }

    /**
     * Builds one organism of the ancestry.
     *
     * @param organismId the organism's id
     * @param parentId   its parent's id, or {@code null} for the founding organism
     * @param labelHash  the hash of its single call frame, or {@code null} for an empty call stack
     * @return the organism state as a tick carries it
     */
    private OrganismState organism(int organismId, Integer parentId, Integer labelHash) {
        OrganismState.Builder builder = OrganismState.newBuilder()
                .setOrganismId(organismId)
                .setBirthTick(0)
                .setProgramId(PROGRAM_ID)
                .setInitialPosition(Vector.newBuilder().addComponents(0).addComponents(0).build())
                .setEnergy(100)
                .setIp(Vector.newBuilder().addComponents(1).addComponents(2).build())
                .setDv(Vector.newBuilder().addComponents(0).addComponents(1).build())
                .addDataPointers(Vector.newBuilder().addComponents(5).addComponents(5).build())
                .setActiveDpIndex(0);
        if (parentId != null) {
            builder.setParentId(parentId);
        }
        if (labelHash != null) {
            builder.addCallStack(ProcFrame.newBuilder()
                    .setLabelHash(labelHash)
                    .setAbsoluteReturnIp(Vector.newBuilder().addComponents(3).addComponents(4).build())
                    .build());
        }
        return builder.build();
    }

    /**
     * Serializes the birth events of an organism whose labels were rewritten with one mask.
     *
     * @param mask the mask that birth applied
     * @return the stored events as the organisms table holds them
     */
    private byte[] labelRewrite(int mask) {
        return StoredMutationEvents.newBuilder()
                .addEvents(StoredMutationEvent.newBuilder()
                        .setPluginClass(LabelRewritePlugin.class.getName())
                        .setKind(LabelRewritePlugin.MUTATION_KIND)
                        .addParams(mask)
                        .addDv(1)
                        .addDv(0)
                        .build())
                .build()
                .toByteArray();
    }
}
