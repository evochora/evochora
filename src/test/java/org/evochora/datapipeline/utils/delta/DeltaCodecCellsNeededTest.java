package org.evochora.datapipeline.utils.delta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.function.LongPredicate;

import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.DeltaType;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.delta.ChunkCorruptedException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Proves that leaving out the cells {@link DeltaCodec#cellsNeededFor} calls unnecessary changes no
 * reconstructed environment.
 * <p>
 * This is the test the whole optimization rests on. The cells of a tick are not standalone data:
 * the environment is rebuilt by walking a chain of deltas, and a link removed by mistake does not
 * announce itself - the reconstruction simply produces a different world. So a chunk is built
 * twice here, once complete and once without those cells, and the reconstructed states are
 * compared cell by cell at every tick a reader would look at.
 */
@Tag("unit")
class DeltaCodecCellsNeededTest {

    private static final int TOTAL_CELLS = 64;
    private static final long RECORDING_INTERVAL = 100;
    private static final int ACCUMULATED_EVERY = 5;
    private static final int RECORDINGS_PER_CHUNK = 20;

    @Test
    void reconstructionIsIdenticalWithoutTheUnneededCells() throws Exception {
        assertAgreement(1000L);    // targets land on accumulated deltas
    }

    @Test
    void reconstructionIsIdenticalWhenTargetsMissTheAccumulatedDeltas() throws Exception {
        assertAgreement(700L);     // targets land between them, so the chain has an incremental tail
    }

    @Test
    void reconstructionIsIdenticalWhenEveryRecordingIsRead() throws Exception {
        assertAgreement(RECORDING_INTERVAL);
    }

    @Test
    void onlyTheAccumulatedDeltasAreNeededWhenTargetsLandOnThem() {
        TickDataChunk chunk = buildChunk();
        BitSet needed = neededFor(chunk, tick -> tick % 1000 == 0);

        // Targets are recordings 0 and 10; recording 0 is the snapshot, and recording 10 is an
        // accumulated delta reached in one step, so it is the only delta needed
        assertThat(needed.stream().boxed()).containsExactly(9);
    }

    @Test
    void theIncrementalTailIsIncludedWhenATargetMissesAnAccumulatedDelta() {
        TickDataChunk chunk = buildChunk();
        BitSet needed = neededFor(chunk, tick -> tick == 700);

        // Recording 7 is reached from the accumulated delta at recording 5, walking 6 and 7
        assertThat(needed.stream().boxed()).containsExactly(4, 5, 6);
    }

    @Test
    void nothingIsNeededWhenNobodyReadsTheEnvironment() {
        TickDataChunk chunk = buildChunk();

        assertThat(neededFor(chunk, tick -> false).isEmpty()).isTrue();
    }

    @Test
    void anInconsistentDirectoryIsRejected() {
        assertThatThrownBy(() -> DeltaCodec.cellsNeededFor(
                List.of(100L, 200L), List.of(DeltaType.INCREMENTAL), 0, tick -> true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("inconsistent");
    }

    @Test
    void decoderRefusesToReconstructFromCellsThatWereNotRead() {
        TickDataChunk stripped = stripCells(buildChunk(), new BitSet());
        DeltaCodec.Decoder decoder = new DeltaCodec.Decoder(TOTAL_CELLS);

        assertThatThrownBy(() -> decoder.decompressTick(stripped, RECORDING_INTERVAL))
            .isInstanceOf(ChunkCorruptedException.class)
            .hasMessageContaining("was read without its cells");
    }

    /**
     * Reconstructs every tick a reader with the given interval would look at, from the complete
     * chunk and from the stripped one, and compares the resulting environments.
     */
    private void assertAgreement(long readInterval) throws Exception {
        TickDataChunk complete = buildChunk();
        BitSet needed = neededFor(complete, tick -> tick % readInterval == 0);
        TickDataChunk stripped = stripCells(complete, needed);

        DeltaCodec.Decoder full = new DeltaCodec.Decoder(TOTAL_CELLS);
        DeltaCodec.Decoder filtered = new DeltaCodec.Decoder(TOTAL_CELLS);

        int compared = 0;
        for (int position = 0; position < RECORDINGS_PER_CHUNK; position++) {
            long tick = position * RECORDING_INTERVAL;
            if (tick % readInterval != 0) {
                continue;
            }
            CellDataColumns expected = full.decompressTick(complete, tick).getCellColumns();
            CellDataColumns actual = filtered.decompressTick(stripped, tick).getCellColumns();
            assertThat(actual)
                .as("environment at tick %d (recording %d)", tick, position)
                .isEqualTo(expected);
            compared++;
        }
        assertThat(compared).as("ticks compared").isGreaterThan(1);
    }

    private BitSet neededFor(TickDataChunk chunk, LongPredicate readsCellsAt) {
        return DeltaCodec.cellsNeededFor(chunk.getDeltaTicksList(), chunk.getDeltaTypesList(),
            chunk.getSnapshot().getTickNumber(), readsCellsAt);
    }

    /**
     * Builds a chunk the way the encoder does: a full snapshot, then incremental deltas carrying
     * the cells changed since the previous recording, and every {@code ACCUMULATED_EVERY}-th
     * recording carrying everything changed since the snapshot. The directory is filled like the
     * encoder fills it.
     */
    private TickDataChunk buildChunk() {
        int[] cells = new int[TOTAL_CELLS];
        for (int i = 0; i < TOTAL_CELLS; i++) {
            cells[i] = i + 1;
        }

        TickDataChunk.Builder chunk = TickDataChunk.newBuilder()
            .setSimulationRunId("test-run")
            .setFirstTick(0)
            .setLastTick((RECORDINGS_PER_CHUNK - 1) * RECORDING_INTERVAL)
            .setTickCount(RECORDINGS_PER_CHUNK)
            .setSnapshot(TickData.newBuilder()
                .setSimulationRunId("test-run")
                .setTickNumber(0)
                .setCellColumns(columnsOf(cells, allIndices()))
                .build());

        List<TickDelta> deltas = new ArrayList<>();
        boolean[] changedSinceSnapshot = new boolean[TOTAL_CELLS];
        for (int position = 1; position < RECORDINGS_PER_CHUNK; position++) {
            int first = (position * 2) % TOTAL_CELLS;
            int second = (position * 2 + 1) % TOTAL_CELLS;
            cells[first] = 1000 + position;
            cells[second] = 2000 + position;
            changedSinceSnapshot[first] = true;
            changedSinceSnapshot[second] = true;

            boolean accumulated = position % ACCUMULATED_EVERY == 0;
            int[] indices = accumulated ? indicesOf(changedSinceSnapshot) : new int[]{first, second};

            deltas.add(TickDelta.newBuilder()
                .setTickNumber(position * RECORDING_INTERVAL)
                .setDeltaType(accumulated ? DeltaType.ACCUMULATED : DeltaType.INCREMENTAL)
                .setChangedCells(columnsOf(cells, indices))
                .build());
        }
        for (TickDelta delta : deltas) {
            chunk.addDeltaTicks(delta.getTickNumber());
            chunk.addDeltaTypes(delta.getDeltaType());
        }
        chunk.addAllDeltas(deltas);
        return chunk.build();
    }

    /** Leaves out the cells of every delta outside the needed set, as the parser would. */
    private TickDataChunk stripCells(TickDataChunk chunk, BitSet needed) {
        TickDataChunk.Builder stripped = chunk.toBuilder().clearDeltas();
        for (int position = 0; position < chunk.getDeltasCount(); position++) {
            TickDelta delta = chunk.getDeltas(position);
            stripped.addDeltas(needed.get(position)
                ? delta
                : delta.toBuilder().clearChangedCells().build());
        }
        return stripped.build();
    }

    private CellDataColumns columnsOf(int[] cells, int[] indices) {
        CellDataColumns.Builder builder = CellDataColumns.newBuilder();
        for (int index : indices) {
            builder.addFlatIndices(index);
            builder.addMoleculeData(cells[index]);
            builder.addOwnerIds(0);
        }
        return builder.build();
    }

    private int[] allIndices() {
        int[] indices = new int[TOTAL_CELLS];
        for (int i = 0; i < TOTAL_CELLS; i++) {
            indices[i] = i;
        }
        return indices;
    }

    private int[] indicesOf(boolean[] flags) {
        int count = 0;
        for (boolean flag : flags) {
            if (flag) count++;
        }
        int[] indices = new int[count];
        int next = 0;
        for (int i = 0; i < flags.length; i++) {
            if (flags[i]) indices[next++] = i;
        }
        return indices;
    }
}
