package org.evochora.cli.rendering.frame.descent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import org.evochora.datapipeline.api.resources.storage.ChunkFieldFilter;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.node.processes.http.api.visualizer.descent.Ancestry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * Tests for {@link DescentHistory}: the pre-pass of the {@code descent} renderer over a synthetic
 * run held in memory.
 * <p>
 * The run used throughout has two founder lines; the line of founder 2 dies out at tick 2, the
 * line of 4 at tick 4:
 * <pre>
 *   1 ── 3 ── 5
 *   │    └── 6
 *   └── 4
 *   2
 * </pre>
 */
@Tag("unit")
class DescentHistoryTest {

    private static DescentRunFixture run() {
        return new DescentRunFixture()
            .batch(0,
                "1:0@10,10 2:0@20,20",
                "1:0@10,10 2:0@20,20 3:1@30,30 4:1@40,40",
                "1:0@10,10 2:0@20,20+ 3:1@30,30 4:1@40,40")
            .batch(3,
                "1:0@10,10+ 3:1@30,30 4:1@40,40 5:3@50,50 6:3@60,60",
                "3:1@30,30 4:1@40,40+ 5:3@50,50 6:3@60,60");
    }

    private static int[] rootsOf(final DescentHistory history) {
        final int[] roots = new int[history.tickCount()];
        for (int i = 0; i < roots.length; i++) {
            roots[i] = history.rootAt(i);
        }
        return roots;
    }

    @Test
    void findsTheRootOfEveryTickAllWhileTwoFounderLinesLiveAndAJumpWhenALineDiesOut() throws Exception {
        final DescentRunFixture run = run();

        final DescentHistory history = DescentHistory.read(run.storage(), run.paths, 0, Long.MAX_VALUE);

        assertThat(history.tickCount()).isEqualTo(5);
        // all while 1 and 2 live; 1 once 2 is dead; 3, the living ancestor of 5 and 6, once 4 is dead
        assertThat(rootsOf(history)).containsExactly(0, 0, 1, 1, 3);
        final Ancestry ancestry = history.ancestry();
        assertThat(new int[]{ancestry.parentOf(1), ancestry.parentOf(2), ancestry.parentOf(5), ancestry.parentOf(6)})
            .containsExactly(0, 0, 3, 3);
        assertThat(ancestry.limit()).isEqualTo(6);
    }

    @Test
    void aRangeStartingLaterKnowsTheParentsFromBeforeItAndEndsAfterItsLastTick() throws Exception {
        final DescentRunFixture run = run();

        final DescentHistory history = DescentHistory.read(run.storage(), run.paths, 2, 2);

        assertThat(history.tickCount()).isEqualTo(1);
        assertThat(history.tickAt(0)).isEqualTo(2L);
        assertThat(history.rootAt(0)).isEqualTo(1);
        assertThat(history.ancestry().limit()).as("no organism of a later tick").isEqualTo(4);
        assertThat(run.read).as("the batch beyond the range is not read").containsExactly(run.paths.get(0));
        assertThatThrownBy(() -> history.indexOf(3)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not read");
    }

    @Test
    void aParentBelowTheFirstTicksTotalThatNoTickNamesMakesAFounderOfAFork() {
        // The fork's first recorded tick had created 10 organisms; 8 and 10 were alive then
        final DescentHistory.Builder builder = new DescentHistory.Builder(Long.MAX_VALUE);
        builder.acceptTick(500, DescentRunFixture.organisms("8:3@1,1 10:5@2,2"), 10);
        builder.acceptTick(501, DescentRunFixture.organisms("8:3@1,1 10:5@2,2 11:8@3,3"), 11);
        builder.acceptTick(502, DescentRunFixture.organisms("8:3@1,1 10:5@2,2+ 11:8@3,3"), 11);

        final DescentHistory history = DescentHistory.of(builder.build(), 0, Long.MAX_VALUE);

        assertThat(history.ancestry().parentOf(8)).isEqualTo(Ancestry.NO_PARENT);
        assertThat(history.ancestry().parentOf(10)).isEqualTo(Ancestry.NO_PARENT);
        assertThat(rootsOf(history)).containsExactly(0, 0, 8);
    }

    @Test
    void aParentAboveTheFirstTicksTotalThatNoTickNamesMeansMissingTicks() {
        final DescentHistory.Builder builder = new DescentHistory.Builder(Long.MAX_VALUE);
        builder.acceptTick(0, DescentRunFixture.organisms("1:0@1,1 2:0@2,2"), 2);

        assertThatThrownBy(() -> builder.acceptTick(1, DescentRunFixture.organisms("1:0@1,1 5:4@3,3"), 5))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("parent 4");
    }

    @Test
    void ticksOutOfOrderAreRejected() {
        final DescentHistory.Builder builder = new DescentHistory.Builder(Long.MAX_VALUE);
        builder.acceptTick(5, DescentRunFixture.organisms("1:0@1,1"), 1);

        assertThatThrownBy(() -> builder.acceptTick(5, DescentRunFixture.organisms("1:0@1,1"), 1))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not in tick order");
    }

    /** Feeds ticks 0, 1, ... to a builder and returns the root of every tick. */
    private static int[] rootsOf(final String... ticks) {
        final DescentHistory.Builder builder = new DescentHistory.Builder(Long.MAX_VALUE);
        for (int tick = 0; tick < ticks.length; tick++) {
            builder.acceptTick(tick, DescentRunFixture.organisms(ticks[tick]), 0);
        }
        return rootsOf(DescentHistory.of(builder.build(), 0, Long.MAX_VALUE));
    }

    @Test
    void theRootStaysWhileItIsAliveAndMovesToItsOneLivingChildWhenItDies() {
        final int[] roots = rootsOf(
            "1:0@1,1",
            "1:0@1,1 2:1@2,2 3:1@3,3",
            "1:0@1,1 2:1@2,2 3:1@3,3+",
            "1:0@1,1 2:1@2,2 4:2@4,4",
            "1:0@1,1+ 2:1@2,2 4:2@4,4",
            "2:1@2,2 4:2@4,4 5:4@5,5");

        // 1 is the root while it lives, even with a single line below it; then 2, alive, stays
        assertThat(roots).containsExactly(1, 1, 1, 1, 2, 2);
    }

    @Test
    void aFounderLineDyingOutMovesTheRootDownSeveralGenerationsAtOnce() {
        final int[] roots = rootsOf(
            "1:0@1,1 2:0@2,2",
            "1:0@1,1 2:0@2,2 3:1@3,3 4:2@4,4",
            "1:0@1,1+ 2:0@2,2+ 3:1@3,3 4:2@4,4 5:3@5,5 6:3@6,6",
            "3:1@3,3+ 4:2@4,4 5:3@5,5 6:3@6,6 7:5@7,7 8:6@8,8",
            "4:2@4,4+ 5:3@5,5 6:3@6,6 7:5@7,7 8:6@8,8");

        // all while a living organism of each founder line lives, even with both founders dead;
        // once the line of 2 is gone, the root passes 1 and lands on 3, the ancestor of 5 and 6
        assertThat(roots).containsExactly(0, 0, 0, 0, 3);
    }

    @Test
    void aNewlyLivingOrganismOutsideTheRootIsRejected() {
        final DescentHistory.Builder builder = new DescentHistory.Builder(Long.MAX_VALUE);
        builder.acceptTick(0, DescentRunFixture.organisms("1:0@1,1 2:0@2,2"), 2);
        builder.acceptTick(1, DescentRunFixture.organisms("1:0@1,1 2:0@2,2+"), 2);

        assertThatThrownBy(() -> builder.acceptTick(2, DescentRunFixture.organisms("1:0@1,1 3:0@3,3"), 3))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not descend from the root 1");
    }

    @Test
    void aRecordReachingFurtherThanTheRangeShowsOnlyTheOrganismsReadByItsEnd() throws Exception {
        final DescentRunFixture run = run();
        final DescentRecord record = DescentHistory.scan(run.storage(), run.paths, Long.MAX_VALUE, 1, (n, t) -> { });

        final DescentHistory history = DescentHistory.of(record, 1, 2);

        assertThat(history.tickCount()).isEqualTo(2);
        assertThat(history.tickAt(0)).isEqualTo(1L);
        assertThat(history.ancestry().limit()).isEqualTo(4);
        assertThat(history.ancestry().parentOf(5)).isEqualTo(Ancestry.UNREAD);
    }

    @Test
    void theBirthTickIsTheFirstRecordedTickThatReadTheOrganism() throws Exception {
        final DescentRunFixture run = run();

        final DescentHistory history = DescentHistory.read(run.storage(), run.paths, 0, Long.MAX_VALUE);

        assertThat(history.birthTickOf(1)).as("a founder").isEqualTo(0L);
        assertThat(history.birthTickOf(2)).as("a founder").isEqualTo(0L);
        assertThat(history.birthTickOf(4)).isEqualTo(1L);
        assertThat(history.birthTickOf(5)).isEqualTo(3L);
        assertThat(history.birthTickOf(6)).isEqualTo(3L);
        assertThatThrownBy(() -> history.birthTickOf(7)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not read");
    }

    @Test
    void aRangeStartingLaterKnowsTheBirthTicksFromBeforeIt() throws Exception {
        final DescentRunFixture run = run();
        final DescentRecord record = DescentHistory.scan(run.storage(), run.paths, Long.MAX_VALUE, 1, (n, t) -> { });

        final DescentHistory history = DescentHistory.of(record, 3, 4);

        assertThat(history.tickAt(0)).isEqualTo(3L);
        assertThat(history.birthTickOf(3)).as("born before the range").isEqualTo(1L);
        assertThat(history.birthTickOf(1)).isEqualTo(0L);
    }

    @Test
    void readingOnSeveralThreadsFindsTheSameAsReadingOnOne() throws Exception {
        final DescentRunFixture run = run().batch(5, "5:3@50,50 6:3@60,60 7:5@70,70");
        final IntArrayList progress = new IntArrayList();

        final DescentRecord one = DescentHistory.scan(run.storage(), run.paths, Long.MAX_VALUE, 1, (n, t) -> { });
        final DescentRecord three = DescentHistory.scan(run.storage(), run.paths, Long.MAX_VALUE, 3,
            (n, t) -> progress.add(n));

        assertThat(three.tickCount()).isEqualTo(one.tickCount()).isEqualTo(6);
        for (int i = 0; i < one.tickCount(); i++) {
            assertThat(three.tickAt(i)).isEqualTo(one.tickAt(i));
            assertThat(three.rootAt(i)).isEqualTo(one.rootAt(i));
            assertThat(three.limitAt(i)).isEqualTo(one.limitAt(i));
        }
        assertThat(three.maxId()).isEqualTo(7);
        assertThat(progress.toIntArray()).as("recorded ticks after each batch file").containsExactly(3, 5, 6);
    }

    @Test
    void aFailedReadOfABatchFileIsRethrownAsItWasThrown() throws Exception {
        final DescentRunFixture run = run();
        final IBatchStorageRead storage = mock(IBatchStorageRead.class);
        doThrow(new IOException("disk gone")).when(storage)
            .forEachChunk(any(StoragePath.class), eq(ChunkFieldFilter.SKIP_CELLS), any());

        assertThatThrownBy(() -> DescentHistory.scan(storage, run.paths, Long.MAX_VALUE, 2, (n, t) -> { }))
            .isInstanceOf(IOException.class)
            .hasMessage("disk gone");
    }

    /** Feeds ticks 0, 1, ... with the given totals to a builder and cuts the whole record. */
    private static DescentHistory historyOf(final long firstTick, final long created, final String... ticks) {
        final DescentHistory.Builder builder = new DescentHistory.Builder(Long.MAX_VALUE);
        for (int i = 0; i < ticks.length; i++) {
            builder.acceptTick(firstTick + i, DescentRunFixture.organisms(ticks[i]), created + i);
        }
        return DescentHistory.of(builder.build(), 0, Long.MAX_VALUE);
    }

    private static byte drift(final int parentShade, final long genome) {
        return LineShade.drift((byte) parentShade, genome);
    }

    @Test
    void theShadeDriftsAlongTheGenomeChain() {
        // 1 founder; 2 changes the genome; 3 keeps 2's genome; 4 changes it again; 5 keeps 1's genome
        final DescentHistory history = historyOf(0, 1,
            "1:0@1,1#10",
            "1:0@1,1#10 2:1@2,2#20^10",
            "1:0@1,1#10 2:1@2,2#20^10 3:2@3,3#20^20 5:1@5,5#10^10",
            "1:0@1,1#10 2:1@2,2#20^10 3:2@3,3#20^20 4:3@4,4#40^20 5:1@5,5#10^10");

        assertThat(history.shadeOf(1)).as("a founder").isZero();
        assertThat(history.shadeOf(2)).as("a changed genome").isEqualTo(drift(0, 20)).isNotZero();
        assertThat(history.shadeOf(3)).as("an unchanged genome").isEqualTo(history.shadeOf(2));
        assertThat(history.shadeOf(4)).as("a second change").isEqualTo(drift(drift(0, 20), 40));
        assertThat(history.shadeOf(5)).as("an unchanged genome below the founder").isZero();
    }

    @Test
    void aChildReadAtTheSameTickAsItsParentTakesTheParentsShade() {
        // 6 is listed before its parent 5, and both are read for the first time at tick 1
        final DescentHistory history = historyOf(0, 1,
            "1:0@1,1#10",
            "6:5@6,6#60^50 1:0@1,1#10 5:1@5,5#50^10 7:5@7,7#50^50");

        assertThat(history.shadeOf(5)).isEqualTo(drift(0, 50));
        assertThat(history.shadeOf(6)).isEqualTo(drift(drift(0, 50), 60));
        assertThat(history.shadeOf(7)).isEqualTo(history.shadeOf(5));
    }

    @Test
    void aParentNeverReadCountsAsShadeZero() {
        // A fork: parents 3 and 5 lived before the first recorded tick, which had created 10 organisms
        final DescentHistory history = historyOf(500, 10,
            "8:3@1,1#80^30 10:5@2,2#50^50",
            "8:3@1,1#80^30 10:5@2,2#50^50 11:8@3,3#80");

        assertThat(history.shadeOf(8)).as("changed from a parent never read").isEqualTo(drift(0, 80));
        assertThat(history.shadeOf(10)).as("unchanged from a parent never read").isZero();
        assertThat(history.shadeOf(11)).as("no parent genome hash: unchanged").isEqualTo(history.shadeOf(8));
    }

    @Test
    void theShadesDoNotDependOnTheNumberOfReadingThreads() throws Exception {
        final DescentRunFixture run = new DescentRunFixture()
            .batch(0, "1:0@1,1#1", "1:0@1,1#1 2:1@2,2#2^1 3:1@3,3#1^1")
            .batch(2, "2:1@2,2#2^1 3:1@3,3#1^1 4:2@4,4#4^2", "4:2@4,4#4^2 5:4@5,5#4^4 6:3@6,6#6^1");

        final DescentRecord one = DescentHistory.scan(run.storage(), run.paths, Long.MAX_VALUE, 1, (n, t) -> { });
        final DescentRecord two = DescentHistory.scan(run.storage(), run.paths, Long.MAX_VALUE, 2, (n, t) -> { });

        assertThat(Arrays.copyOf(two.shades(), 7)).isEqualTo(Arrays.copyOf(one.shades(), 7));
        assertThat(one.shades()[5]).isEqualTo(drift(drift(0, 2), 4)).isEqualTo(one.shades()[4]);
        assertThat(DescentHistory.of(one, 0, Long.MAX_VALUE).shadeOf(6)).isEqualTo(drift(0, 6));
    }

    /**
     * A synthetic run of a few hundred ticks with births, deaths and organisms born dead: the
     * incremental root of every tick equals the common ancestor of the tree of the living built
     * from scratch.
     */
    @Test
    void theIncrementalRootEqualsTheCommonAncestorOfTheLivingAtEveryTick() {
        final Random random = new Random(20260927L);
        final DescentHistory.Builder builder = new DescentHistory.Builder(Long.MAX_VALUE);
        final int[] parents = new int[100_000];
        Arrays.fill(parents, Ancestry.UNREAD);
        IntArrayList living = new IntArrayList();
        int nextId = 1;
        for (; nextId <= 4; nextId++) {
            parents[nextId] = Ancestry.NO_PARENT;
            living.add(nextId);
        }
        final int ticks = 600;
        final int[] expected = new int[ticks];
        for (int tick = 0; tick < ticks; tick++) {
            final boolean[] dies = new boolean[living.size()];
            boolean anySurvives = false;
            for (int i = 0; i < dies.length; i++) {
                dies[i] = random.nextDouble() < 0.02 + living.size() / 60.0;
                anySurvives |= !dies[i];
            }
            // The population never dies out
            dies[0] &= anySurvives;
            final StringBuilder list = new StringBuilder();
            final IntArrayList next = new IntArrayList();
            for (int i = 0; i < dies.length; i++) {
                final int id = living.getInt(i);
                list.append(id).append(':').append(parents[id]).append("@0,0").append(dies[i] ? "+ " : " ");
                if (!dies[i]) {
                    next.add(id);
                }
                if (random.nextDouble() < 0.3) {
                    final boolean bornDead = random.nextDouble() < 0.1;
                    parents[nextId] = id;
                    list.append(nextId).append(':').append(id).append("@0,0").append(bornDead ? "+ " : " ");
                    if (!bornDead) {
                        next.add(nextId);
                    }
                    nextId++;
                }
            }
            builder.acceptTick(tick, DescentRunFixture.organisms(list.toString()), nextId - 1);
            expected[tick] = new Ancestry(parents, nextId - 1).living(next, Ancestry.NO_PARENT).commonAncestor();
            living = next;
        }

        final DescentRecord record = builder.build();
        final int[] roots = new int[ticks];
        for (int tick = 0; tick < ticks; tick++) {
            roots[tick] = record.rootAt(tick);
        }
        assertThat(roots).isEqualTo(expected);
        assertThat(record.distinctRoots()).as("the root moved often enough to mean something").isGreaterThan(10);
    }
}
