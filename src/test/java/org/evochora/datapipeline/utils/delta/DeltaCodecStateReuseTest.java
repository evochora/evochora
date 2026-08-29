package org.evochora.datapipeline.utils.delta;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.DeltaType;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Pins that how a tick is reached does not change what is reconstructed.
 * <p>
 * The decoder keeps the state of the tick it last produced and moves it forward when the next
 * request lies ahead, instead of rebuilding from the snapshot every time. Whether it walks
 * forward, jumps over an accumulated delta or starts from scratch must make no difference to the
 * resulting environment - every reader of a chunk relies on that, and any change to how the
 * decoder advances has to keep it true.
 */
@Tag("unit")
class DeltaCodecStateReuseTest {

    private static final int TOTAL_CELLS = 64;
    private static final long TICK_STEP = 100;
    private static final int ACCUMULATED_EVERY = 5;
    private static final int RECORDINGS = 20;

    @Test
    void walkingForwardYieldsWhatRebuildingFromScratchYields() throws Exception {
        TickDataChunk chunk = buildChunk();
        DeltaCodec.Decoder walking = new DeltaCodec.Decoder(TOTAL_CELLS);

        for (int position = 0; position < RECORDINGS; position++) {
            long tick = position * TICK_STEP;
            CellDataColumns fromScratch = new DeltaCodec.Decoder(TOTAL_CELLS)
                .decompressTick(chunk, tick).getCellColumns();

            assertThat(walking.decompressTick(chunk, tick).getCellColumns())
                .as("environment at tick %d (recording %d)", tick, position)
                .isEqualTo(fromScratch);
        }
    }

    @Test
    void jumpingOntoAccumulatedDeltasYieldsTheSame() throws Exception {
        TickDataChunk chunk = buildChunk();
        DeltaCodec.Decoder jumping = new DeltaCodec.Decoder(TOTAL_CELLS);

        // Recordings 5, 10 and 15 are the accumulated deltas
        for (int position = ACCUMULATED_EVERY; position < RECORDINGS; position += ACCUMULATED_EVERY) {
            long tick = position * TICK_STEP;
            CellDataColumns fromScratch = new DeltaCodec.Decoder(TOTAL_CELLS)
                .decompressTick(chunk, tick).getCellColumns();

            assertThat(jumping.decompressTick(chunk, tick).getCellColumns())
                .as("environment at accumulated recording %d", position)
                .isEqualTo(fromScratch);
        }
    }

    @Test
    void jumpingPastSeveralAccumulatedDeltasYieldsTheSame() throws Exception {
        TickDataChunk chunk = buildChunk();
        DeltaCodec.Decoder jumping = new DeltaCodec.Decoder(TOTAL_CELLS);

        // From the snapshot straight past three accumulated deltas, then on to a later one
        jumping.decompressTick(chunk, 0);
        for (long tick : new long[]{16 * TICK_STEP, 19 * TICK_STEP}) {
            CellDataColumns fromScratch = new DeltaCodec.Decoder(TOTAL_CELLS)
                .decompressTick(chunk, tick).getCellColumns();

            assertThat(jumping.decompressTick(chunk, tick).getCellColumns())
                .as("environment at tick %d", tick)
                .isEqualTo(fromScratch);
        }
    }

    @Test
    void goingBackwardsAndForwardsAgainYieldsTheSame() throws Exception {
        TickDataChunk chunk = buildChunk();
        DeltaCodec.Decoder wandering = new DeltaCodec.Decoder(TOTAL_CELLS);

        wandering.decompressTick(chunk, 17 * TICK_STEP);
        wandering.decompressTick(chunk, 3 * TICK_STEP);
        long tick = 12 * TICK_STEP;

        CellDataColumns fromScratch = new DeltaCodec.Decoder(TOTAL_CELLS)
            .decompressTick(chunk, tick).getCellColumns();

        assertThat(wandering.decompressTick(chunk, tick).getCellColumns()).isEqualTo(fromScratch);
    }

    @Test
    void everyTickOfTheChunkAgreesWithTheSequentialReconstruction() throws Exception {
        TickDataChunk chunk = buildChunk();
        List<TickData> sequential = new DeltaCodec.Decoder(TOTAL_CELLS).decompressChunk(chunk);
        DeltaCodec.Decoder byTick = new DeltaCodec.Decoder(TOTAL_CELLS);

        assertThat(sequential).hasSize(RECORDINGS);
        for (TickData expected : sequential) {
            assertThat(byTick.decompressTick(chunk, expected.getTickNumber()).getCellColumns())
                .as("environment at tick %d", expected.getTickNumber())
                .isEqualTo(expected.getCellColumns());
        }
    }

    @Test
    void anAccumulatedDeltaOverwritesWhatTheSkippedDeltasDidToTheState() throws Exception {
        // Hand-built so the expected environment is derived from the format's definition rather
        // than from a second run of the same code.
        //
        //   snapshot     cells 0..7 = 1..8
        //   recording 1  cell 0 -> 100
        //   recording 2  cell 1 -> 200
        //   recording 3  cell 0 -> 0      (emptied again)
        //   recording 4  cell 2 -> 400
        //   recording 5  ACCUMULATED: everything changed since the snapshot, so cells 0, 1 and 2
        //                with their values at recording 5: 0, 200, 400
        int[] snapshotCells = {1, 2, 3, 4, 5, 6, 7, 8};
        TickDataChunk chunk = TickDataChunk.newBuilder()
            .setSimulationRunId("test-run")
            .setFirstTick(0)
            .setLastTick(500)
            .setTickCount(6)
            .setSnapshot(TickData.newBuilder().setTickNumber(0)
                .setCellColumns(columnsOf(snapshotCells, allEight())).build())
            .addDeltaTicks(100).addDeltaTypes(DeltaType.INCREMENTAL)
            .addDeltaTicks(200).addDeltaTypes(DeltaType.INCREMENTAL)
            .addDeltaTicks(300).addDeltaTypes(DeltaType.INCREMENTAL)
            .addDeltaTicks(400).addDeltaTypes(DeltaType.INCREMENTAL)
            .addDeltaTicks(500).addDeltaTypes(DeltaType.ACCUMULATED)
            .addDeltas(delta(100, DeltaType.INCREMENTAL, new int[]{0}, new int[]{100}))
            .addDeltas(delta(200, DeltaType.INCREMENTAL, new int[]{1}, new int[]{200}))
            .addDeltas(delta(300, DeltaType.INCREMENTAL, new int[]{0}, new int[]{0}))
            .addDeltas(delta(400, DeltaType.INCREMENTAL, new int[]{2}, new int[]{400}))
            .addDeltas(delta(500, DeltaType.ACCUMULATED,
                new int[]{0, 1, 2}, new int[]{0, 200, 400}))
            .build();

        DeltaCodec.Decoder decoder = new DeltaCodec.Decoder(8);
        // Stand on recording 2, where cell 0 still holds 100
        CellDataColumns atTwo = decoder.decompressTick(chunk, 200).getCellColumns();
        assertThat(valueAt(atTwo, 0)).isEqualTo(100);

        // Jump over recordings 3 and 4 onto the accumulated delta
        CellDataColumns atFive = decoder.decompressTick(chunk, 500).getCellColumns();

        assertThat(valueAt(atFive, 0)).as("emptied in a skipped delta, cleared by the accumulated one")
            .isEqualTo(0);
        assertThat(valueAt(atFive, 1)).isEqualTo(200);
        assertThat(valueAt(atFive, 2)).as("changed only in a skipped delta").isEqualTo(400);
        for (int index = 3; index < 8; index++) {
            assertThat(valueAt(atFive, index)).as("untouched since the snapshot, cell %d", index)
                .isEqualTo(index + 1);
        }
    }

    @Test
    void jumpingIntoAnotherChunkStartsFromThatChunksSnapshot() throws Exception {
        // An accumulated delta accumulates since the snapshot of ITS chunk. Carrying a state from
        // one chunk into another and applying the other chunk's accumulated delta on top would
        // leave the first chunk's cells standing wherever the second chunk changed nothing.
        //
        //   chunk A   snapshot at tick 0:    cells 0..3 = 10, 20, 30, 40
        //             tick 100 INCREMENTAL:  cell 0 -> 11
        //             tick 200 ACCUMULATED:  cell 0 -> 11
        //   chunk B   snapshot at tick 1000: cells 0..3 = 50, 60, 70, 80
        //             tick 1100 INCREMENTAL: cell 1 -> 61
        //             tick 1200 ACCUMULATED: cell 1 -> 61
        TickDataChunk chunkA = twoDeltaChunk(0, new int[]{10, 20, 30, 40}, 0, 11);
        TickDataChunk chunkB = twoDeltaChunk(1000, new int[]{50, 60, 70, 80}, 1, 61);

        DeltaCodec.Decoder decoder = new DeltaCodec.Decoder(4);
        CellDataColumns inA = decoder.decompressTick(chunkA, 200).getCellColumns();
        assertThat(valueAt(inA, 0)).isEqualTo(11);
        assertThat(valueAt(inA, 2)).isEqualTo(30);

        // Straight onto the accumulated delta of the other chunk
        CellDataColumns inB = decoder.decompressTick(chunkB, 1200).getCellColumns();

        assertThat(valueAt(inB, 0)).as("chunk B's snapshot value, not chunk A's state").isEqualTo(50);
        assertThat(valueAt(inB, 1)).isEqualTo(61);
        assertThat(valueAt(inB, 2)).as("untouched in B, so B's snapshot value").isEqualTo(70);
        assertThat(valueAt(inB, 3)).isEqualTo(80);

        // And back again
        CellDataColumns backInA = decoder.decompressTick(chunkA, 200).getCellColumns();
        assertThat(valueAt(backInA, 0)).isEqualTo(11);
        assertThat(valueAt(backInA, 1)).isEqualTo(20);
        assertThat(valueAt(backInA, 2)).isEqualTo(30);
    }

    /**
     * A chunk of three recordings: a full snapshot, an incremental delta changing one cell, and an
     * accumulated delta holding that same change - the shape the encoder produces when exactly one
     * cell changed since the snapshot.
     */
    private TickDataChunk twoDeltaChunk(long firstTick, int[] snapshotCells, int changedIndex, int changedValue) {
        int[] indices = new int[snapshotCells.length];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = i;
        }
        return TickDataChunk.newBuilder()
            .setSimulationRunId("test-run")
            .setFirstTick(firstTick)
            .setLastTick(firstTick + 200)
            .setTickCount(3)
            .setSnapshot(TickData.newBuilder().setTickNumber(firstTick)
                .setCellColumns(columnsOf(snapshotCells, indices)).build())
            .addDeltaTicks(firstTick + 100).addDeltaTypes(DeltaType.INCREMENTAL)
            .addDeltaTicks(firstTick + 200).addDeltaTypes(DeltaType.ACCUMULATED)
            .addDeltas(delta(firstTick + 100, DeltaType.INCREMENTAL,
                new int[]{changedIndex}, new int[]{changedValue}))
            .addDeltas(delta(firstTick + 200, DeltaType.ACCUMULATED,
                new int[]{changedIndex}, new int[]{changedValue}))
            .build();
    }

    private TickDelta delta(long tick, DeltaType type, int[] indices, int[] values) {
        CellDataColumns.Builder cells = CellDataColumns.newBuilder();
        for (int i = 0; i < indices.length; i++) {
            cells.addFlatIndices(indices[i]);
            cells.addMoleculeData(values[i]);
            cells.addOwnerIds(0);
        }
        return TickDelta.newBuilder()
            .setTickNumber(tick)
            .setDeltaType(type)
            .setChangedCells(cells.build())
            .build();
    }

    private int[] allEight() {
        int[] indices = new int[8];
        for (int i = 0; i < 8; i++) {
            indices[i] = i;
        }
        return indices;
    }

    /** Value of a cell in a reconstructed environment, 0 when it is not listed. */
    private int valueAt(CellDataColumns cells, int flatIndex) {
        for (int i = 0; i < cells.getFlatIndicesCount(); i++) {
            if (cells.getFlatIndices(i) == flatIndex) {
                return cells.getMoleculeData(i);
            }
        }
        return 0;
    }

    /**
     * Builds a chunk shaped like the encoder's output: a full snapshot, incremental deltas holding
     * what changed since the previous recording, and every fifth recording an accumulated delta
     * holding everything changed since the snapshot.
     */
    private TickDataChunk buildChunk() {
        int[] cells = new int[TOTAL_CELLS];
        for (int i = 0; i < TOTAL_CELLS; i++) {
            cells[i] = i + 1;
        }

        TickDataChunk.Builder chunk = TickDataChunk.newBuilder()
            .setSimulationRunId("test-run")
            .setFirstTick(0)
            .setLastTick((RECORDINGS - 1) * TICK_STEP)
            .setTickCount(RECORDINGS)
            .setSnapshot(TickData.newBuilder()
                .setSimulationRunId("test-run")
                .setTickNumber(0)
                .setCellColumns(columnsOf(cells, allIndices()))
                .build());

        List<TickDelta> deltas = new ArrayList<>();
        boolean[] changedSinceSnapshot = new boolean[TOTAL_CELLS];
        for (int position = 1; position < RECORDINGS; position++) {
            int first = (position * 3) % TOTAL_CELLS;
            int second = (position * 7) % TOTAL_CELLS;
            // A cell emptied again is part of the changes too - a delta carries the value, not a flag
            cells[first] = position % 4 == 0 ? 0 : 1000 + position;
            cells[second] = 2000 + position;
            changedSinceSnapshot[first] = true;
            changedSinceSnapshot[second] = true;

            boolean accumulated = position % ACCUMULATED_EVERY == 0;
            int[] indices = accumulated ? indicesOf(changedSinceSnapshot) : new int[]{first, second};

            deltas.add(TickDelta.newBuilder()
                .setTickNumber(position * TICK_STEP)
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
