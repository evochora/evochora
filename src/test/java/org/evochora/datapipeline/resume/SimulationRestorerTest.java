package org.evochora.datapipeline.resume;

import static org.assertj.core.api.Assertions.assertThat;

import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.RegisterValue;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.IRandomProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link SimulationRestorer}.
 * <p>
 * Tests that simulation state is correctly restored from a snapshot.
 * Since resume always happens from a snapshot (chunk start), there is
 * no accumulated delta handling to test.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
@AllowLog(level = LogLevel.INFO, loggerPattern = ".*SimulationRestorer.*")
class SimulationRestorerTest {

    private static final String TEST_RUN_ID = "20250127-123456-test-run";

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    private IRandomProvider randomProvider;

    @BeforeEach
    void setUp() {
        // Use real random provider instead of mock since Organism needs deriveFor()
        randomProvider = new SeededRandomProvider(42L);
    }

    // ==================== Happy Path Tests ====================

    @Test
    void restore_FromSnapshot_CreatesSimulation() {
        // Create minimal metadata
        SimulationMetadata metadata = createMinimalMetadata();

        // Create snapshot with one organism
        TickData snapshot = createSnapshot(1000, 100);

        // Create checkpoint (always from snapshot)
        ResumeCheckpoint checkpoint = new ResumeCheckpoint(metadata, snapshot);

        // Restore
        SimulationRestorer.RestoredState state = SimulationRestorer.restore(checkpoint, randomProvider);
        Simulation simulation = state.simulation();

        // Verify simulation state
        assertThat(simulation.getCurrentTick()).isEqualTo(1000);
        assertThat(simulation.getTotalOrganismsCreatedCount()).isEqualTo(100);
        assertThat(simulation.getEnvironment().getShape()).isEqualTo(new int[]{100, 100});
        assertThat(simulation.getOrganisms()).hasSize(1);
    }

    @Test
    void restore_OrganismState_AllFieldsRestored() {
        SimulationMetadata metadata = createMinimalMetadata();

        // Create detailed organism state
        OrganismState orgState = OrganismState.newBuilder()
            .setOrganismId(42)
            .setParentId(10)
            .setBirthTick(500)
            .setProgramId("test-program")
            .setEnergy(1000)
            .setIp(createVector(25, 30))
            .setDv(createVector(1, 0))
            .setInitialPosition(createVector(20, 25))
            .setEntropyRegister(50)
            .setMoleculeMarkerRegister(3)
            .addDataPointers(createVector(10, 10))
            .addDataPointers(createVector(20, 20))
            .setActiveDpIndex(1)
            .addDataRegisters(RegisterValue.newBuilder().setScalar(100).build())
            .addProcedureRegisters(RegisterValue.newBuilder().setScalar(200).build())
            .setIsDead(false)
            .build();

        TickData snapshot = TickData.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setTickNumber(500)
            .setCaptureTimeMs(System.currentTimeMillis())
            .setTotalOrganismsCreated(50)
            .setCellColumns(CellDataColumns.newBuilder().build())
            .addOrganisms(orgState)
            .build();

        ResumeCheckpoint checkpoint = new ResumeCheckpoint(metadata, snapshot);
        SimulationRestorer.RestoredState state = SimulationRestorer.restore(checkpoint, randomProvider);
        Simulation simulation = state.simulation();

        assertThat(simulation.getOrganisms()).hasSize(1);
        Organism org = simulation.getOrganisms().get(0);

        assertThat(org.getId()).isEqualTo(42);
        assertThat(org.getParentId()).isEqualTo(10);
        assertThat(org.getBirthTick()).isEqualTo(500);
        assertThat(org.getProgramId()).isEqualTo("test-program");
        assertThat(org.getEr()).isEqualTo(1000);
        assertThat(org.getIp()).isEqualTo(new int[]{25, 30});
        assertThat(org.getDv()).isEqualTo(new int[]{1, 0});
        assertThat(org.getSr()).isEqualTo(50);
        assertThat(org.getMr()).isEqualTo(3);
        assertThat(org.getActiveDpIndex()).isEqualTo(1);
        assertThat(org.getDr(0)).isEqualTo(100);
        assertThat(org.getPr(0)).isEqualTo(200);
    }

    @Test
    void restore_DeadOrganisms_Skipped() {
        SimulationMetadata metadata = createMinimalMetadata();

        // Create snapshot with one live and one dead organism
        OrganismState liveOrg = createOrganismState(1, 500);
        OrganismState deadOrg = OrganismState.newBuilder()
            .setOrganismId(2)
            .setBirthTick(100)
            .setEnergy(0)
            .setIp(createVector(0, 0))
            .setDv(createVector(1, 0))
            .setIsDead(true)
            .build();

        TickData snapshot = TickData.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setTickNumber(1000)
            .setCaptureTimeMs(System.currentTimeMillis())
            .setTotalOrganismsCreated(100)
            .setCellColumns(CellDataColumns.newBuilder().build())
            .addOrganisms(liveOrg)
            .addOrganisms(deadOrg)
            .build();

        ResumeCheckpoint checkpoint = new ResumeCheckpoint(metadata, snapshot);
        SimulationRestorer.RestoredState state = SimulationRestorer.restore(checkpoint, randomProvider);
        Simulation simulation = state.simulation();

        // Only live organism should be restored
        assertThat(simulation.getOrganisms()).hasSize(1);
        assertThat(simulation.getOrganisms().get(0).getId()).isEqualTo(1);
    }

    @Test
    void restore_CellData_EnvironmentPopulated() {
        SimulationMetadata metadata = createMinimalMetadata();

        // Create cell data with some cells
        // flatIndexToCoord with shape [100, 100] and flatIndex 510:
        //   coord[1] = 510 % 100 = 10
        //   coord[0] = 510 / 100 = 5
        // So flatIndex 510 -> coord [5, 10]
        //
        // Pack molecule data: type=TYPE_DATA, value=42, marker=0
        int packedMolecule = Config.TYPE_DATA | 42;

        CellDataColumns cells = CellDataColumns.newBuilder()
            .addFlatIndices(510)  // coord [5, 10]
            .addMoleculeData(packedMolecule)
            .addOwnerIds(7)
            .build();

        TickData snapshot = TickData.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setTickNumber(1000)
            .setCaptureTimeMs(System.currentTimeMillis())
            .setTotalOrganismsCreated(50)
            .setCellColumns(cells)
            .build();

        ResumeCheckpoint checkpoint = new ResumeCheckpoint(metadata, snapshot);
        SimulationRestorer.RestoredState state = SimulationRestorer.restore(checkpoint, randomProvider);
        Simulation simulation = state.simulation();

        // Verify cell was set at coord [5, 10]
        var molecule = simulation.getEnvironment().getMolecule(5, 10);
        assertThat(molecule.type()).isEqualTo(Config.TYPE_DATA);  // type() returns shifted value
        assertThat(molecule.value()).isEqualTo(42);
        assertThat(simulation.getEnvironment().getOwnerId(5, 10)).isEqualTo(7);
    }

    // ==================== Genome Hash Restoration ====================

    @Test
    void restore_GenomeHashes_RestoredFromSnapshot() {
        SimulationMetadata metadata = createMinimalMetadata();

        TickData snapshot = TickData.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setTickNumber(1000)
            .setCaptureTimeMs(System.currentTimeMillis())
            .setTotalOrganismsCreated(100)
            .setTotalUniqueGenomes(3)
            .addAllGenomeHashesEverSeen(111L)
            .addAllGenomeHashesEverSeen(222L)
            .addAllGenomeHashesEverSeen(333L)
            .setCellColumns(CellDataColumns.newBuilder().build())
            .addOrganisms(createOrganismState(1, 500))
            .build();

        ResumeCheckpoint checkpoint = new ResumeCheckpoint(metadata, snapshot);
        SimulationRestorer.RestoredState state = SimulationRestorer.restore(checkpoint, randomProvider);
        Simulation simulation = state.simulation();

        assertThat(simulation.getTotalUniqueGenomesCount()).isEqualTo(3);
        assertThat(simulation.getAllGenomesEverSeen()).containsExactlyInAnyOrder(111L, 222L, 333L);
    }

    @Test
    void restore_OldSimulation_FallsBackToLivingOrganisms() {
        SimulationMetadata metadata = createMinimalMetadata();

        // Old simulation without genome hash fields (defaults to empty list)
        OrganismState org1 = OrganismState.newBuilder()
            .setOrganismId(1)
            .setBirthTick(0)
            .setEnergy(500)
            .setGenomeHash(111L)
            .setIp(createVector(10, 10))
            .setDv(createVector(1, 0))
            .setInitialPosition(createVector(5, 5))
            .setIsDead(false)
            .build();

        OrganismState org2 = OrganismState.newBuilder()
            .setOrganismId(2)
            .setBirthTick(0)
            .setEnergy(300)
            .setGenomeHash(222L)
            .setIp(createVector(20, 20))
            .setDv(createVector(0, 1))
            .setInitialPosition(createVector(15, 15))
            .setIsDead(false)
            .build();

        TickData snapshot = TickData.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setTickNumber(1000)
            .setCaptureTimeMs(System.currentTimeMillis())
            .setTotalOrganismsCreated(50)
            // No genome hash fields set (old simulation format)
            .setCellColumns(CellDataColumns.newBuilder().build())
            .addOrganisms(org1)
            .addOrganisms(org2)
            .build();

        ResumeCheckpoint checkpoint = new ResumeCheckpoint(metadata, snapshot);
        SimulationRestorer.RestoredState state = SimulationRestorer.restore(checkpoint, randomProvider);
        Simulation simulation = state.simulation();

        // Fallback: reconstructed from living organisms
        assertThat(simulation.getTotalUniqueGenomesCount()).isEqualTo(2);
        assertThat(simulation.getAllGenomesEverSeen()).containsExactlyInAnyOrder(111L, 222L);
    }

    // ==================== Helper Methods ====================

    private SimulationMetadata createMinimalMetadata() {
        String configJson = """
            {
              "runtime": {
                "organism": {
                  "max-energy": 32767,
                  "max-entropy": 8191,
                  "error-penalty-cost": 10
                },
                "thermodynamics": {
                  "default": {
                    "className": "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy",
                    "options": {
                      "base-energy": 1,
                      "base-entropy": 1
                    }
                  },
                  "overrides": {
                    "instructions": {},
                    "families": {}
                  }
                }
              }
            }
            """;

        // Build the full resolvedConfigJson with environment and runtime
        String fullConfigJson = TestMetadataHelper.builder()
            .shape(100, 100)
            .toroidal(true)
            .samplingInterval(1)
            .accumulatedDeltaInterval(5)
            .snapshotInterval(20)
            .chunkInterval(1)
            .build();

        // Parse and merge with runtime config
        com.typesafe.config.Config parsedConfig = com.typesafe.config.ConfigFactory.parseString(fullConfigJson);
        com.typesafe.config.Config runtimeConfig = com.typesafe.config.ConfigFactory.parseString(configJson);
        String mergedJson = parsedConfig.withFallback(runtimeConfig)
            .root().render(com.typesafe.config.ConfigRenderOptions.concise());

        return SimulationMetadata.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setStartTimeMs(System.currentTimeMillis())
            .setInitialSeed(42)
            .setResolvedConfigJson(mergedJson)
            .build();
    }

    private TickData createSnapshot(long tick, long totalOrganisms) {
        return TickData.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setTickNumber(tick)
            .setCaptureTimeMs(System.currentTimeMillis())
            .setTotalOrganismsCreated(totalOrganisms)
            .setCellColumns(CellDataColumns.newBuilder().build())
            .addOrganisms(createOrganismState(1, 500))
            .build();
    }

    private OrganismState createOrganismState(int id, int energy) {
        return OrganismState.newBuilder()
            .setOrganismId(id)
            .setBirthTick(0)
            .setEnergy(energy)
            .setIp(createVector(10, 10))
            .setDv(createVector(1, 0))
            .setInitialPosition(createVector(5, 5))
            .setIsDead(false)
            .build();
    }

    private Vector createVector(int... components) {
        Vector.Builder builder = Vector.newBuilder();
        for (int c : components) {
            builder.addComponents(c);
        }
        return builder.build();
    }
}
