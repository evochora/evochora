package org.evochora.datapipeline.resources.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.CellStateTestHelper;
import org.evochora.datapipeline.api.contracts.DeltaType;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.resources.storage.ChunkFieldFilter;
import org.evochora.datapipeline.api.resources.storage.ITickRelevance;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Tests reading a chunk that carries deltas, with and without a narrowing relevance.
 * <p>
 * A chunk of a real run holds a snapshot and one delta per further tick, and a directory naming
 * the tick and type of each of them. Chunks built for other tests hold a snapshot alone, so the
 * whole delta path of the reader - the directory, the per-delta relevance, the verification of
 * one against the other - is only exercised here.
 */
@Tag("unit")
class FileSystemStorageRelevanceTest {

    @TempDir
    Path tempDir;

    private FileSystemStorageResource storage;

    @BeforeEach
    void setUp() {
        Config config = ConfigFactory.parseMap(
            Map.of("rootDirectory", tempDir.toAbsolutePath().toString()));
        storage = new FileSystemStorageResource("test-storage", config);
    }

    @Test
    void aChunkWithDeltasIsReadBack() throws Exception {
        storage.writeChunkBatchStreaming(List.of(chunkWithDeltas()).iterator());

        List<TickDataChunk> read = readBack(ChunkFieldFilter.ALL, ITickRelevance.EVERYTHING);

        assertThat(read).hasSize(1);
        TickDataChunk chunk = read.get(0);
        assertThat(chunk.getTickCount()).isEqualTo(4);
        assertThat(chunk.getDeltasCount()).isEqualTo(3);
        assertThat(chunk.getDeltaTicksList()).containsExactly(100L, 200L, 300L);
        assertThat(chunk.getDeltaTypesList()).containsExactly(
            DeltaType.INCREMENTAL, DeltaType.INCREMENTAL, DeltaType.ACCUMULATED);
    }

    @Test
    void organismsAreBuiltOnlyForTicksTheReaderConsumes() throws Exception {
        storage.writeChunkBatchStreaming(List.of(chunkWithDeltas()).iterator());

        // A reader looking at tick 200 alone
        List<TickDataChunk> read = readBack(ChunkFieldFilter.ALL, new ITickRelevance() {
            @Override public boolean readsOrganismsAt(long tickNumber) { return tickNumber == 200L; }
            @Override public boolean readsCellsAt(long tickNumber) { return false; }
        });

        TickDataChunk chunk = read.get(0);
        assertThat(chunk.getSnapshot().getOrganismsCount()).isZero();
        assertThat(organismsOfDeltaAt(chunk, 100L)).isZero();
        assertThat(organismsOfDeltaAt(chunk, 200L)).isEqualTo(1);
        assertThat(organismsOfDeltaAt(chunk, 300L)).isZero();

        // The chunk still describes itself completely
        assertThat(chunk.getDeltasCount()).isEqualTo(3);
        assertThat(chunk.getDeltaTicksList()).containsExactly(100L, 200L, 300L);
    }

    @Test
    void cellsAreBuiltForTheDeltasAReconstructionWalksThrough() throws Exception {
        storage.writeChunkBatchStreaming(List.of(chunkWithDeltas()).iterator());

        // Reaching tick 200 means applying the snapshot and the deltas up to it; the accumulated
        // delta at 300 lies beyond and its cells are never touched
        List<TickDataChunk> read = readBack(ChunkFieldFilter.ALL, new ITickRelevance() {
            @Override public boolean readsOrganismsAt(long tickNumber) { return false; }
            @Override public boolean readsCellsAt(long tickNumber) { return tickNumber == 200L; }
        });

        TickDataChunk chunk = read.get(0);
        assertThat(chunk.getSnapshot().getCellColumns().getFlatIndicesCount()).isPositive();
        assertThat(cellsOfDeltaAt(chunk, 100L)).isPositive();
        assertThat(cellsOfDeltaAt(chunk, 200L)).isPositive();
        assertThat(cellsOfDeltaAt(chunk, 300L)).isZero();
    }

    @Test
    void theMonitoredReaderPassesTheRelevanceOn() throws Exception {
        storage.writeChunkBatchStreaming(List.of(chunkWithDeltas()).iterator());
        StoragePath path = storage.listBatchFiles("", null, 10).getFilenames().get(0);

        var monitored = (org.evochora.datapipeline.api.resources.storage.IBatchStorageRead)
            storage.getWrappedResource(new org.evochora.datapipeline.api.resources.ResourceContext(
                "test-service", "storage-port", "storage-read", "test-storage", Map.of()));

        List<TickDataChunk> read = new ArrayList<>();
        monitored.forEachChunk(path, ChunkFieldFilter.ALL, new ITickRelevance() {
            @Override public boolean readsOrganismsAt(long tickNumber) { return tickNumber == 200L; }
            @Override public boolean readsCellsAt(long tickNumber) { return false; }
        }, read::add);

        assertThat(read).hasSize(1);
        assertThat(organismsOfDeltaAt(read.get(0), 200L)).isEqualTo(1);
        assertThat(organismsOfDeltaAt(read.get(0), 100L)).isZero();
    }

    private List<TickDataChunk> readBack(ChunkFieldFilter filter, ITickRelevance relevance)
            throws Exception {
        StoragePath path = storage.listBatchFiles("", null, 10).getFilenames().get(0);
        List<TickDataChunk> read = new ArrayList<>();
        storage.forEachChunk(path, filter, relevance, read::add);
        return read;
    }

    private int organismsOfDeltaAt(TickDataChunk chunk, long tick) {
        return deltaAt(chunk, tick).getOrganismsCount();
    }

    private int cellsOfDeltaAt(TickDataChunk chunk, long tick) {
        return deltaAt(chunk, tick).getChangedCells().getFlatIndicesCount();
    }

    private TickDelta deltaAt(TickDataChunk chunk, long tick) {
        return chunk.getDeltasList().stream()
            .filter(delta -> delta.getTickNumber() == tick)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no delta at tick " + tick));
    }

    /**
     * A chunk shaped like one a run produces: a snapshot, three deltas, and a directory naming
     * them, written in the order the encoder writes it.
     */
    private TickDataChunk chunkWithDeltas() {
        TickDataChunk.Builder chunk = TickDataChunk.newBuilder()
            .setSimulationRunId("test-sim")
            .setFirstTick(0)
            .setLastTick(300)
            .setTickCount(4)
            .setSnapshot(TickData.newBuilder()
                .setTickNumber(0)
                .setSimulationRunId("test-sim")
                .addOrganisms(organism(1))
                .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                    CellStateTestHelper.createCellState(0, 1, 1, 50, 0))))
                .build());

        List<TickDelta> deltas = List.of(
            delta(100, DeltaType.INCREMENTAL, 2),
            delta(200, DeltaType.INCREMENTAL, 3),
            delta(300, DeltaType.ACCUMULATED, 4));

        for (TickDelta delta : deltas) {
            chunk.addDeltaTicks(delta.getTickNumber());
            chunk.addDeltaTypes(delta.getDeltaType());
        }
        for (TickDelta delta : deltas) {
            chunk.addDeltas(delta);
        }
        return chunk.build();
    }

    private TickDelta delta(long tick, DeltaType type, int organismId) {
        return TickDelta.newBuilder()
            .setTickNumber(tick)
            .setDeltaType(type)
            .addOrganisms(organism(organismId))
            .setChangedCells(CellStateTestHelper.createColumnsFromCells(List.of(
                CellStateTestHelper.createCellState(organismId, organismId, 1, 60, 0))))
            .build();
    }

    private OrganismState organism(int id) {
        return OrganismState.newBuilder().setOrganismId(id).setGenomeHash(1000 + id).build();
    }
}
