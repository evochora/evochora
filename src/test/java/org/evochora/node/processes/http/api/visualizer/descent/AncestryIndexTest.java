package org.evochora.node.processes.http.api.visualizer.descent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;

import org.evochora.junit.extensions.logging.ExpectLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * Tests for {@link AncestryIndex}: the paged catch-up, gaps, the fork boundary, the states and
 * the view it publishes, against an in-memory table behind a mocked reader.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class AncestryIndexTest {

    private static final int PAGE = 2;

    private static AncestryIndex indexOf(final FakeRun run) {
        return run.indexes(PAGE).forRun(FakeRun.RUN_ID).index();
    }

    private static Int2IntOpenHashMap memo() {
        return Ancestry.newMemo();
    }

    @Test
    void readsTheTableInPagesAndPublishesTheCursor() throws Exception {
        final FakeRun run = new FakeRun().with(1, 0, 2, 1, 3, 1, 4, 2, 5, 4, 6, 0);
        final AncestryIndex index = indexOf(run);

        assertThat(index.snapshot().stateFor(6)).isEqualTo(AncestryIndex.State.LOADING);
        index.requestCatchUp(6);
        index.requestCatchUp(6);
        assertThat(run.executor.queued()).as("one pending catch-up per run").isEqualTo(1);
        run.executor.runAll();

        final AncestryIndex.Snapshot view = index.snapshot();
        assertThat(view.cursor()).isEqualTo(6);
        assertThat(view.stateFor(6)).isEqualTo(AncestryIndex.State.READY);
        assertThat(new int[]{view.parentOf(1), view.parentOf(2), view.parentOf(5), view.parentOf(6)})
            .containsExactly(0, 1, 4, 0);
        assertThat(view.parentOf(7)).isEqualTo(Ancestry.UNREAD);
        assertThat(index.progressOf(view)).isEqualTo(1.0);
        // Three full pages of two and the empty one that ends the table
        verify(run.reader).readParents(0, PAGE);
        verify(run.reader).readParents(2, PAGE);
        verify(run.reader).readParents(4, PAGE);
        verify(run.reader).readParents(6, PAGE);
    }

    @Test
    void anIdAPageSkippedIsUnknownUntilItsRowArrives() throws Exception {
        // Organism 3 is not indexed yet; 4 and 5 descend from it
        final FakeRun run = new FakeRun().with(1, 0, 2, 1, 4, 3, 5, 4);
        final AncestryIndex index = indexOf(run);
        index.requestCatchUp(5);
        run.executor.runAll();

        AncestryIndex.Snapshot view = index.snapshot();
        assertThat(view.stateFor(5)).isEqualTo(AncestryIndex.State.READY);
        assertThat(view.ancestry().lineOf(0, 5, memo(), new IntArrayList())).isEqualTo(Ancestry.UNKNOWN);
        assertThat(view.ancestry().lineOf(0, 2, memo(), new IntArrayList())).isEqualTo(1);
        final long before = view.version();

        run.with(3, 1, 6, 5);
        index.requestCatchUp(6);
        clearInvocations(run.reader);
        run.executor.runAll();

        view = index.snapshot();
        assertThat(view.ancestry().lineOf(0, 5, memo(), new IntArrayList())).isEqualTo(1);
        assertThat(view.ancestry().lineOf(0, 6, memo(), new IntArrayList())).isEqualTo(1);
        assertThat(view.version()).isGreaterThan(before);
        // The gap is re-read bounded to itself
        verify(run.reader).readParents(2, 1);
    }

    @Test
    void aParentAtOrBelowTheForkBoundaryWithoutARowMakesAFounder() throws Exception {
        // The fork's first tick had created 10 organisms: 8 and 10 were alive then, their
        // parents 3 and 5 were not. 12 lies above the boundary and is not indexed yet.
        final FakeRun run = new FakeRun().with(8, 3, 9, 8, 10, 5, 11, 9, 13, 12);
        run.forkedAt(500, 10);
        // The newest tick has created 20 organisms
        when(run.reader.readTotalOrganismsCreated(100L)).thenReturn(20);
        final AncestryIndex index = indexOf(run);
        index.requestCatchUp(13);
        run.executor.runAll();

        final AncestryIndex.Snapshot view = index.snapshot();
        assertThat(view.boundary()).isEqualTo(10);
        // Progress counts only the ids above the boundary: (13 - 10) / (20 - 10)
        assertThat(index.progressOf(view)).isCloseTo(0.3, within(1e-9));
        assertThat(view.parentOf(8)).isEqualTo(Ancestry.NO_PARENT);
        assertThat(view.parentOf(10)).isEqualTo(Ancestry.NO_PARENT);
        assertThat(view.parentOf(9)).isEqualTo(8);
        assertThat(view.ancestry().lineOf(0, 11, memo(), new IntArrayList())).isEqualTo(8);
        assertThat(view.ancestry().lineOf(0, 13, memo(), new IntArrayList())).isEqualTo(Ancestry.UNKNOWN);

        // The next catch-up re-reads the gap above the boundary, never the ids below it
        run.with(14, 13);
        clearInvocations(run.reader);
        index.requestCatchUp(14);
        run.executor.runAll();
        verify(run.reader).readParents(11, 1);
        verify(run.reader, never()).readParents(eq(0), anyInt());
    }

    @Test
    void staysLoadingWithoutReadingWhileTheForksFirstTickIsNotIndexed() throws Exception {
        final FakeRun run = new FakeRun().with(8, 3);
        run.forkedAtUnindexedTick(500);
        final AncestryIndex index = indexOf(run);
        index.requestCatchUp(8);
        run.executor.runAll();

        assertThat(index.snapshot().stateFor(8)).isEqualTo(AncestryIndex.State.LOADING);
        assertThat(index.snapshot().boundary()).isEqualTo(-1);
        assertThat(index.progressOf(index.snapshot())).isZero();
        verify(run.reader, never()).readParents(anyInt(), anyInt());
    }

    @Test
    @ExpectLog(level = LogLevel.ERROR, messagePattern = "Reading the ancestry of run 'run-descent' failed")
    void aFailedCatchUpIsReportedAndARequestAfterTheCooldownRetries() throws Exception {
        final FakeRun run = new FakeRun().with(1, 0, 2, 1);
        run.failure = new SQLException("disk gone");
        final AncestryIndex index = indexOf(run);
        index.requestCatchUp(2);
        run.executor.runAll();

        AncestryIndex.Snapshot view = index.snapshot();
        assertThat(view.stateFor(2)).isEqualTo(AncestryIndex.State.FAILED);
        assertThat(view.failure()).hasMessage("disk gone");
        // A tick the index already covers is still reported as failed
        assertThat(view.stateFor(0)).isEqualTo(AncestryIndex.State.FAILED);

        run.failure = null;
        run.nanos += AncestryIndex.COOLDOWN_NANOS - 1;
        index.requestCatchUp(0);
        assertThat(run.executor.queued()).as("no retry within the cooldown").isZero();

        run.nanos += 1;
        index.requestCatchUp(0);
        assertThat(run.executor.queued()).isEqualTo(1);
        run.executor.runAll();

        view = index.snapshot();
        assertThat(view.failure()).isNull();
        assertThat(view.stateFor(2)).isEqualTo(AncestryIndex.State.READY);
    }

    @Test
    void theIndexOfARunNobodyAskedForWithinTheIdleTimeIsDroppedWhenAnotherRunIsAskedFor() throws Exception {
        final FakeRun run = new FakeRun().with(1, 0, 2, 1);
        final long idle = 1_000;
        final AncestryIndexes indexes =
            new AncestryIndexes(run.provider, run.executor, PAGE, () -> run.nanos, 2, idle);
        final AncestryIndex first = indexes.forRun("run-a").index();

        run.nanos += idle;
        indexes.forRun("run-b");
        assertThat(indexes.keptRuns()).as("asked for within the idle time").isEqualTo(2);
        assertThat(indexes.forRun("run-a").index()).isSameAs(first);

        run.nanos += idle + 1;
        assertThat(indexes.forRun("run-a").index()).as("never dropped by its own request").isSameAs(first);
        assertThat(indexes.keptRuns()).as("run-b rested longer than the idle time").isEqualTo(1);

        run.nanos += idle + 1;
        indexes.forRun("run-b");
        assertThat(indexes.keptRuns()).isEqualTo(1);
        assertThat(indexes.forRun("run-a").index()).as("read anew").isNotSameAs(first);
    }

    @Test
    void aDroppedIndexReadsNothingMore() throws Exception {
        final FakeRun run = new FakeRun().with(1, 0, 2, 1);
        final AncestryIndexes indexes =
            new AncestryIndexes(run.provider, run.executor, PAGE, () -> run.nanos, 2, 1_000);
        final AncestryIndex dropped = indexes.forRun("run-a").index();
        dropped.requestCatchUp(2);

        run.nanos += 1_001;
        indexes.forRun("run-b");
        run.executor.runAll();

        assertThat(dropped.snapshot().stateFor(2)).isEqualTo(AncestryIndex.State.LOADING);
        verify(run.reader, never()).readParents(anyInt(), anyInt());
    }

    @Test
    void closingStopsFurtherCatchUps() throws Exception {
        final FakeRun run = new FakeRun().with(1, 0);
        final AncestryIndexes indexes = run.indexes(PAGE);
        indexes.close();

        indexes.forRun(FakeRun.RUN_ID).view(1);

        assertThat(run.executor.queued()).isZero();
        assertThat(indexes.forRun(FakeRun.RUN_ID).index().snapshot().stateFor(1))
            .isEqualTo(AncestryIndex.State.LOADING);
    }

    @Test
    void growsTheArrayAndPublishesEveryPageBeforeItsCursor() throws Exception {
        final FakeRun run = new FakeRun().with(1, 0);
        for (int id = 2; id <= 3000; id++) {
            run.with(id, id - 1);
        }
        final AncestryIndex index = run.index(500);
        run.onRead = after -> {
            final AncestryIndex.Snapshot view = index.snapshot();
            // A page starts where the published cursor ends, and everything below it is there
            assertThat(view.cursor()).isEqualTo(after);
            if (view.cursor() > 0) {
                assertThat(view.parentOf(view.cursor())).isEqualTo(view.cursor() - 1);
                assertThat(view.parentOf(1)).isEqualTo(Ancestry.NO_PARENT);
            }
        };
        index.requestCatchUp(3000);
        run.executor.runAll();

        final AncestryIndex.Snapshot view = index.snapshot();
        assertThat(view.cursor()).isEqualTo(3000);
        assertThat(view.parentOf(3000)).isEqualTo(2999);
        // One reader for the boundary and the newest total, then one per page: six full pages
        // and the empty one that ends the table
        verify(run.provider, times(8)).createReader(FakeRun.RUN_ID);
    }

    @Test
    void aGapWiderThanAPageIsReadInPagesAndSplitIntoWhatIsStillMissing() throws Exception {
        final FakeRun run = new FakeRun().with(1, 0, 2, 1, 10, 1);
        final AncestryIndex index = run.index(2);
        index.requestCatchUp(10);
        run.executor.runAll();
        // Once as the second page of the table, once as the first page of the gap 3..9, which
        // already finds 10, beyond the gap
        verify(run.reader, times(2)).readParents(2, 2);

        run.with(4, 1, 5, 4, 8, 1);
        clearInvocations(run.reader);
        run.nanos += AncestryIndex.COOLDOWN_NANOS;
        index.requestGapReread();
        run.executor.runAll();
        // Two pages of the gap: 4 and 5, then 8 and 10, which lies beyond it
        verify(run.reader).readParents(2, 2);
        verify(run.reader).readParents(5, 2);
        AncestryIndex.Snapshot view = index.snapshot();
        assertThat(view.parentOf(5)).isEqualTo(4);
        assertThat(view.parentOf(8)).isEqualTo(1);
        assertThat(view.parentOf(3)).isEqualTo(Ancestry.UNREAD);

        // What is still missing is 3, 6..7 and 9, each re-read on its own
        clearInvocations(run.reader);
        run.nanos += AncestryIndex.COOLDOWN_NANOS;
        index.requestGapReread();
        run.executor.runAll();
        verify(run.reader).readParents(2, 1);
        verify(run.reader).readParents(5, 2);
        verify(run.reader).readParents(8, 1);
        view = index.snapshot();
        assertThat(view.parentOf(6)).isEqualTo(Ancestry.UNREAD);
    }

    @Test
    void theStopSignalEndsACatchUpBetweenPagesAndKeepsTheGapsNotReached() throws Exception {
        final FakeRun run = new FakeRun().with(1, 0, 2, 1, 4, 1, 5, 1, 7, 1, 8, 1);
        final AncestryIndex index = run.index(2);
        run.onRead = after -> run.stopping |= after == 5;
        index.requestCatchUp(8);
        run.executor.runAll();

        // The page in progress is published, the next one and the gaps are not read
        assertThat(index.snapshot().cursor()).isEqualTo(8);
        verify(run.reader, never()).readParents(8, 2);
        verify(run.reader, never()).readParents(2, 1);
        assertThat(index.snapshot().failure()).isNull();

        run.stopping = false;
        run.with(3, 1, 6, 1);
        run.onRead = after -> run.stopping |= after == 2;
        run.nanos += AncestryIndex.COOLDOWN_NANOS;
        index.requestGapReread();
        run.executor.runAll();
        assertThat(index.snapshot().parentOf(3)).isEqualTo(1);
        assertThat(index.snapshot().parentOf(6)).as("the gap not reached is kept").isEqualTo(Ancestry.UNREAD);

        run.stopping = false;
        run.onRead = after -> { };
        run.nanos += AncestryIndex.COOLDOWN_NANOS;
        index.requestGapReread();
        run.executor.runAll();
        assertThat(index.snapshot().parentOf(6)).isEqualTo(1);
    }

    @Test
    void aForkIsReadyOnceTheBoundaryReachesTheTickWhenItsLastOrganismHasNoRow() throws Exception {
        // The first tick had created 10 organisms, but 10 itself died before it was recorded
        final FakeRun run = new FakeRun().with(8, 3, 9, 8);
        run.forkedAt(500, 10);
        final AncestryIndex index = run.index(5);
        index.requestCatchUp(10);
        run.executor.runAll();

        assertThat(index.snapshot().cursor()).isEqualTo(9);
        assertThat(index.snapshot().stateFor(10)).isEqualTo(AncestryIndex.State.READY);
        index.requestCatchUp(10);
        assertThat(run.executor.queued()).as("no catch-up for a tick the index has reached").isZero();
    }
}
