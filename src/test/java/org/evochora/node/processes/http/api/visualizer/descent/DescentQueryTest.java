package org.evochora.node.processes.http.api.visualizer.descent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;

import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;

import org.evochora.datapipeline.api.resources.database.OrganismNotFoundException;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickSummary;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.node.processes.http.api.visualizer.OrganismController;
import org.evochora.node.spi.ServiceRegistry;
import org.evochora.node.processes.http.api.visualizer.dto.DescentDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Tests for {@link DescentQuery}: the answer for a root, the common ancestor of the living, the
 * landings and the sizes of the lines, against an in-memory table behind a mocked reader.
 * <p>
 * The family used throughout:
 * <pre>
 *   1 ── 2 ── 4 ── 5
 *   │         └── 7
 *   └── 3
 *   6
 * </pre>
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class DescentQueryTest {

    private FakeRun run;
    private DescentQuery query;

    @BeforeEach
    void setUp() throws Exception {
        run = new FakeRun().with(1, 0, 2, 1, 3, 1, 4, 2, 5, 4, 6, 0, 7, 4);
        query = run.indexes(3).forRun(FakeRun.RUN_ID);
    }

    /** Takes a view, lets the catch-up it asks for run, and describes the tick. */
    private DescentDto describeReady(final RootRequest root, final List<OrganismTickSummary> organisms)
            throws Exception {
        query.view(7);
        run.executor.runAll();
        final DescentQuery.View view = query.view(7);
        assertThat(view.state()).isEqualTo(AncestryIndex.State.READY);
        return FakeRun.describe(view, root, organisms, run.reader);
    }

    @Test
    void answersTheLinesOfARootWithSizesRanksAndTheLiving() throws Exception {
        final DescentDto d = describeReady(RootRequest.parse("1"), FakeRun.tick(new int[]{5, 3, 6}, 7));

        assertThat(d.state()).isEqualTo("ready");
        assertThat(d.organismsInRun()).isEqualTo(7);
        assertThat(d.root().id()).isEqualTo(1);
        assertThat(d.root().birthTick()).isEqualTo(10L);
        assertThat(d.root().position()).containsExactly(1, 2);
        assertThat(d.root().descendants()).isEqualTo(5L);
        assertThat(d.lines()).extracting(DescentDto.Line::id).containsExactly(2, 3);
        assertThat(d.lines()).extracting(DescentDto.Line::descendants).containsExactly(4L, 1L);
        assertThat(d.lines()).extracting(DescentDto.Line::colour).containsExactly(0, 1);
        assertThat(d.lines()).extracting(DescentDto.Line::living).containsExactly(1, 1);
        assertThat(d.lineOf()).containsEntry(5, 2).containsEntry(3, 3).containsEntry(6, 0).containsEntry(7, 2);
    }

    @Test
    void landsDownWhereTheLivingOfALineSplitAndUpWhereTheLivingSplitAboveTheRoot() throws Exception {
        final DescentDto one = describeReady(RootRequest.parse("1"), FakeRun.tick(new int[]{5, 7, 3}));
        final DescentDto.Line line2 = one.lines().get(0);
        assertThat(line2.id()).isEqualTo(2);
        // 2 leads to the living through 4 alone; 4 has two living children
        assertThat(line2.landing()).isEqualTo(new DescentDto.Landing(4, 1));
        // 3 is alive itself: nothing to skip
        assertThat(one.lines().get(1).landing()).isNull();
        assertThat(one.up()).isEqualTo(new DescentDto.Up(0, new DescentDto.Landing(0, 0)));

        final DescentDto four = describeReady(RootRequest.parse("4"), FakeRun.tick(new int[]{5, 7, 3}));
        // One step up is 2; above it the living split at 1, one generation further
        assertThat(four.up()).isEqualTo(new DescentDto.Up(2, new DescentDto.Landing(1, 1)));
    }

    @Test
    void autoResolvesToTheCommonAncestorOfTheLiving() throws Exception {
        assertThat(describeReady(RootRequest.parse("auto"), FakeRun.tick(new int[]{5, 3})).root().id())
            .isEqualTo(1);
        assertThat(describeReady(RootRequest.parse("auto"), FakeRun.tick(new int[]{5, 7}, 3)).root().id())
            .as("the dead do not count").isEqualTo(4);
        assertThat(describeReady(RootRequest.parse("auto"), FakeRun.tick(new int[]{4, 5})).root().id())
            .as("a living ancestor of the living is their common ancestor").isEqualTo(4);
        assertThat(describeReady(RootRequest.parse("auto"), FakeRun.tick(new int[]{5, 6})).root().id())
            .as("two founder lines alive").isEqualTo(0);
    }

    @Test
    void allHasEveryFounderAsALineAndNoWayUp() throws Exception {
        final DescentDto d = describeReady(RootRequest.parse("all"), FakeRun.tick(new int[]{5, 6}));

        assertThat(d.root()).isEqualTo(new DescentDto.Root(0, null, null, null, 7L));
        assertThat(d.lines()).extracting(DescentDto.Line::id).containsExactly(1, 6);
        assertThat(d.lines()).extracting(DescentDto.Line::descendants).containsExactly(6L, 1L);
        assertThat(d.lines().get(0).landing()).isEqualTo(new DescentDto.Landing(5, 3));
        assertThat(d.up()).isNull();
        assertThat(d.lineOf()).containsEntry(5, 1).containsEntry(6, 6);
    }

    @Test
    void whileLoadingTheAnswerCarriesTheRootButNoLines() throws Exception {
        final DescentQuery.View view = query.view(7);
        assertThat(view.state()).isEqualTo(AncestryIndex.State.LOADING);

        final DescentDto byId = FakeRun.describe(view, RootRequest.parse("4"), FakeRun.tick(new int[]{5}), run.reader);
        assertThat(byId.state()).isEqualTo("loading");
        assertThat(byId.organismsInRun()).as("no catch-up has read the newest tick yet").isZero();
        assertThat(byId.root().id()).isEqualTo(4);
        assertThat(byId.root().descendants()).isNull();
        assertThat(byId.lines()).isEmpty();
        assertThat(byId.lineOf()).isEmpty();
        assertThat(byId.up()).isNull();

        assertThat(FakeRun.describe(view, RootRequest.parse("auto"), FakeRun.tick(new int[]{5}), run.reader).root())
            .as("auto cannot be resolved yet").isNull();
    }

    @Test
    void aRootThatIsNotIndexedIsNotFound() {
        final DescentQuery.View view = query.view(7);
        assertThatThrownBy(() -> FakeRun.describe(view, RootRequest.parse("99"), FakeRun.tick(new int[]{5}), run.reader))
            .isInstanceOf(OrganismNotFoundException.class);
    }

    @Test
    void aFilledGapChangesTheVersionAndWithItTheSizes() throws Exception {
        // 3 is missing at first, so its descendant 8 has no known line
        run.rows.remove(3);
        run.with(8, 3);
        query.view(8);
        run.executor.runAll();
        DescentQuery.View view = query.view(8);
        DescentDto d = FakeRun.describe(view, RootRequest.parse("1"), FakeRun.tick(new int[]{5, 8}), run.reader);
        assertThat(d.lines()).extracting(DescentDto.Line::id).containsExactly(2);
        assertThat(d.lineOf()).containsEntry(8, -1);
        final long before = view.version();

        run.with(3, 1, 9, 0);
        query.view(9);
        run.executor.runAll();
        view = query.view(9);
        d = FakeRun.describe(view, RootRequest.parse("1"), FakeRun.tick(new int[]{5, 8}), run.reader);
        assertThat(view.version()).isGreaterThan(before);
        assertThat(d.lines()).extracting(DescentDto.Line::id).containsExactly(2, 3);
        assertThat(d.lines()).extracting(DescentDto.Line::descendants).containsExactly(4L, 2L);
        assertThat(d.lineOf()).containsEntry(8, 3);
        assertThat(query.fullCounts()).as("a filled gap counts from scratch").isEqualTo(2);
    }

    @Test
    void anUnknownOrganismOfAReadyTickAsksForACatchUpAtMostOncePerCooldown() throws Exception {
        final long second = java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
        final List<OrganismTickSummary> withUnknown = FakeRun.tick(new int[]{5, 8});
        // 3 is not indexed when the index passes it, and the run does not grow any more
        run.rows.remove(3);
        run.with(8, 3);
        query.view(8);
        run.executor.runAll();

        final DescentQuery.View view = query.view(8);
        assertThat(view.state()).isEqualTo(AncestryIndex.State.READY);
        assertThat(run.executor.queued()).as("the cursor alone asks for nothing").isZero();
        FakeRun.describe(view, RootRequest.parse("1"), FakeRun.tick(new int[]{5}), run.reader);
        assertThat(run.executor.queued()).as("every line known").isZero();

        run.nanos = 4 * second;
        assertThat(FakeRun.describe(view, RootRequest.parse("1"), withUnknown, run.reader).lineOf()).containsEntry(8, -1);
        assertThat(run.executor.queued()).as("within the cooldown after the last catch-up").isZero();

        run.nanos = 6 * second;
        FakeRun.describe(view, RootRequest.parse("1"), withUnknown, run.reader);
        FakeRun.describe(view, RootRequest.parse("1"), withUnknown, run.reader);
        assertThat(run.executor.queued()).as("at most one pending catch-up").isEqualTo(1);
        run.executor.runAll();

        // The row still has not arrived: the catch-up that just ended starts the cooldown again
        run.nanos = 10 * second;
        FakeRun.describe(query.view(8), RootRequest.parse("1"), withUnknown, run.reader);
        assertThat(run.executor.queued()).isZero();

        run.with(3, 1);
        run.nanos = 12 * second;
        FakeRun.describe(query.view(8), RootRequest.parse("1"), withUnknown, run.reader);
        assertThat(run.executor.queued()).as("after the cooldown").isEqualTo(1);
        run.executor.runAll();
        assertThat(FakeRun.describe(query.view(8), RootRequest.parse("1"), withUnknown, run.reader).lineOf())
            .containsEntry(8, 3);
    }

    @Test
    void aForkRootAtOrBelowTheBoundaryHasItsLinesSizesAndWayUp() throws Exception {
        final FakeRun fork = new FakeRun().with(8, 3, 9, 8, 10, 5, 11, 9, 12, 11);
        fork.forkedAt(500, 10);
        final DescentQuery q = fork.indexes(3).forRun(FakeRun.RUN_ID);
        q.view(12);
        fork.executor.runAll();
        final List<OrganismTickSummary> tick = FakeRun.tick(new int[]{12, 10});

        final DescentDto eight = FakeRun.describe(q.view(12), RootRequest.parse("8"), tick, fork.reader);
        assertThat(eight.lines()).extracting(DescentDto.Line::id).containsExactly(9);
        assertThat(eight.lines()).extracting(DescentDto.Line::descendants).containsExactly(3L);
        assertThat(eight.up()).as("8 is a founder of the fork")
            .isEqualTo(new DescentDto.Up(0, new DescentDto.Landing(0, 0)));

        final DescentDto nine = FakeRun.describe(q.view(12), RootRequest.parse("9"), tick, fork.reader);
        assertThat(nine.lines()).extracting(DescentDto.Line::id).containsExactly(11);
        assertThat(nine.up()).isEqualTo(new DescentDto.Up(8, new DescentDto.Landing(0, 1)));
        assertThat(nine.lineOf()).containsEntry(12, 11).containsEntry(10, 0);
    }

    @Test
    void theOrganismsInAForkCountOnlyTheIdsAboveTheBoundary() throws Exception {
        final FakeRun fork = new FakeRun().with(8, 3, 9, 8, 10, 5, 11, 9, 12, 11);
        fork.forkedAt(500, 10);
        final DescentQuery q = fork.indexes(3).forRun(FakeRun.RUN_ID);
        q.view(12);
        fork.executor.runAll();

        final DescentDto all = FakeRun.describe(q.view(12), RootRequest.parse("all"),
            FakeRun.tick(new int[]{12, 10}), fork.reader);

        // The newest tick has created 12 organisms, 10 of them in the parent run
        assertThat(all.organismsInRun()).isEqualTo(2);
        assertThat(all.root().descendants()).as("the founders 8 and 10 lie at or below the boundary")
            .isEqualTo(5L);
    }

    @Test
    void theOrganismsInTheRunAreNeverFewerThanTheIndexHasRead() throws Exception {
        // The newest tick's total was read before rows of later ticks arrived
        when(run.reader.readTotalOrganismsCreated(100L)).thenReturn(5);

        final DescentDto d = describeReady(RootRequest.parse("all"), FakeRun.tick(new int[]{5}));

        assertThat(d.organismsInRun()).isEqualTo(7);
    }

    @Test
    void aRootAboveTheCursorHasNoLinesAndNoWayUp() throws Exception {
        final DescentDto ready = describeReady(RootRequest.parse("1"), FakeRun.tick(new int[]{5}));
        assertThat(ready.state()).isEqualTo("ready");
        // 20 is indexed in the table but not read into the index yet
        run.with(20, 5);
        final DescentDto d = FakeRun.describe(query.view(7), RootRequest.parse("20"), FakeRun.tick(new int[]{5}), run.reader);

        assertThat(d.root().id()).isEqualTo(20);
        assertThat(d.root().descendants()).isZero();
        assertThat(d.lines()).isEmpty();
        assertThat(d.up()).isNull();
        assertThat(d.lineOf()).containsEntry(5, 0);
    }

    @Test
    void theSizesAreCountedOncePerVersionAndExtendedByANewPage() throws Exception {
        describeReady(RootRequest.parse("1"), FakeRun.tick(new int[]{5}));
        describeReady(RootRequest.parse("1"), FakeRun.tick(new int[]{5}));
        assertThat(query.fullCounts()).as("the same version is served from the cache").isEqualTo(1);

        run.with(8, 3, 9, 8);
        query.view(9);
        run.executor.runAll();
        final DescentDto d = FakeRun.describe(query.view(9), RootRequest.parse("1"), FakeRun.tick(new int[]{5, 9}), run.reader);

        assertThat(query.fullCounts()).as("a new page extends the count").isEqualTo(1);
        assertThat(d.lines()).extracting(DescentDto.Line::id).containsExactly(2, 3);
        assertThat(d.lines()).extracting(DescentDto.Line::descendants).containsExactly(4L, 3L);
        assertThat(d.root().descendants()).isEqualTo(7L);
        // The extended count equals a count from scratch
        final DescentQuery.LineSizes fresh = new DescentQuery(query.index(), 2).sizesOf(query.index().snapshot(), 1);
        assertThat(fresh.ids()).containsExactly(2, 3);
        assertThat(fresh.sizes()).containsExactly(4, 3);
    }

    @Test
    void autoIsNotResolvedWhileALivingOrganismHasAnUnknownAncestry() throws Exception {
        run.rows.remove(3);
        run.with(8, 3);
        query.view(8);
        run.executor.runAll();
        run.nanos += AncestryIndex.GAP_REREAD_COOLDOWN_NANOS;

        final DescentDto d = FakeRun.describe(query.view(8), RootRequest.parse("auto"), FakeRun.tick(new int[]{5, 8}), run.reader);

        assertThat(d.state()).isEqualTo("ready");
        assertThat(d.root()).isNull();
        assertThat(d.lines()).isEmpty();
        assertThat(d.lineOf()).isEmpty();
        assertThat(run.executor.queued()).as("the gaps are asked for").isEqualTo(1);
    }

    @Test
    void theWayUpHasNoLandingWhenAnAncestorOnTheWayIsUnknown() throws Exception {
        run.rows.remove(2);
        query.view(7);
        run.executor.runAll();

        final DescentDto d = FakeRun.describe(query.view(7), RootRequest.parse("5"), FakeRun.tick(new int[]{5}), run.reader);

        assertThat(d.up()).isEqualTo(new DescentDto.Up(4, null));
    }

    @Test
    void theControllerPassesItsAttributionArraysOptionToTheQueries() throws Exception {
        final ServiceRegistry registry = new ServiceRegistry();
        registry.register(IDatabaseReaderProvider.class, run.provider);

        assertThat(queriesOf(new OrganismController(registry,
            ConfigFactory.parseString("descent.attribution-arrays = 5"))).attributionArrays()).isEqualTo(5);
        assertThat(queriesOf(new OrganismController(registry, ConfigFactory.empty())).attributionArrays())
            .as("default").isEqualTo(2);
        assertThatThrownBy(() -> new OrganismController(registry,
            ConfigFactory.parseString("descent.attribution-arrays = -1")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrganismController(registry,
            ConfigFactory.parseString("descent.attribution-arrays = many")))
            .isInstanceOf(ConfigException.WrongType.class);
    }

    /** The queries a controller answers the run {@link FakeRun#RUN_ID} with. */
    private static DescentQuery queriesOf(final OrganismController controller) throws Exception {
        final Field field = OrganismController.class.getDeclaredField("ancestryIndexes");
        field.setAccessible(true);
        final DescentQuery queries = ((AncestryIndexes) field.get(controller)).forRun(FakeRun.RUN_ID);
        controller.close();
        return queries;
    }

    @Test
    void withoutAttributionArraysEveryNewVersionIsCountedFromScratch() throws Exception {
        final DescentQuery none = new AncestryIndexes(run.provider, run.executor, 3, () -> run.nanos, 0)
            .forRun(FakeRun.RUN_ID);
        none.view(7);
        run.executor.runAll();
        none.sizesOf(none.index().snapshot(), 1);

        run.with(8, 3);
        none.view(8);
        run.executor.runAll();
        final DescentQuery.LineSizes sizes = none.sizesOf(none.index().snapshot(), 1);

        assertThat(none.fullCounts()).isEqualTo(2);
        assertThat(sizes.sizes()).containsExactly(4, 2);
    }

    @Test
    void onlyTheEightLargestLinesGetARank() throws Exception {
        final FakeRun wide = new FakeRun().with(1, 0);
        for (int child = 2; child <= 13; child++) {
            wide.with(child, 1);
        }
        final DescentQuery q = wide.indexes(5).forRun(FakeRun.RUN_ID);
        q.view(13);
        wide.executor.runAll();

        final DescentDto d = FakeRun.describe(q.view(13), RootRequest.parse("1"), FakeRun.tick(new int[]{2}), wide.reader);

        assertThat(d.lines()).hasSize(12);
        assertThat(d.lines().subList(0, 8)).extracting(DescentDto.Line::colour)
            .containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
        assertThat(d.lines().subList(8, 12)).extracting(DescentDto.Line::colour).containsOnlyNulls();
    }

    @Test
    void aMalformedRootIsRejected() {
        for (final String bad : new String[]{"", "x", "0", "-3", "ALL", "1.5"}) {
            assertThatThrownBy(() -> RootRequest.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(RootRequest.parse(" 12 ")).isEqualTo(new RootRequest(RootRequest.Kind.ORGANISM, 12));
    }
}
