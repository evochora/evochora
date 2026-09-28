package org.evochora.node.processes.http.api.visualizer.descent;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import org.evochora.datapipeline.api.contracts.ForkOrigin;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.datapipeline.api.resources.database.TickNotFoundException;
import org.evochora.datapipeline.api.resources.database.dto.OrganismStaticInfo;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickSummary;
import org.evochora.datapipeline.api.resources.database.dto.ParentRows;
import org.evochora.datapipeline.api.resources.database.dto.TickRange;
import org.evochora.node.processes.http.api.visualizer.dto.DescentDto;

/**
 * An in-memory run for the descent tests: an {@code organisms} table of ids and parents behind a
 * mocked reader, and an executor whose tasks run only when the test says so.
 * <p>
 * No I/O. Not thread-safe; a test drives it from its own thread.
 */
final class FakeRun {

    /** The run id every index of this fake is created for. */
    static final String RUN_ID = "run-descent";

    /** The rows of the organisms table: organism id to parent id, 0 for a founder. */
    final NavigableMap<Integer, Integer> rows = new TreeMap<>();

    /** The reader every {@code createReader} call returns; its close does nothing. */
    final IDatabaseReader reader = mock(IDatabaseReader.class);

    /** The provider the indexes take their readers from. */
    final IDatabaseReaderProvider provider = mock(IDatabaseReaderProvider.class);

    /** The executor the catch-ups are queued on until the test runs them. */
    final ManualExecutor executor = new ManualExecutor();

    /** The time the indexes read, in nanoseconds; tests move it forward by hand. */
    long nanos;

    /** When set, every read of the parent relation fails with it. */
    SQLException failure;

    /** The stop signal of indexes built by {@link #index(int)}. */
    boolean stopping;

    /** Runs before every read of the parent relation, with its afterId; does nothing by default. */
    java.util.function.IntConsumer onRead = afterId -> { };

    /**
     * Creates a fresh, unforked run whose newest tick has created as many organisms as the table
     * holds rows.
     */
    FakeRun() throws Exception {
        when(provider.createReader(anyString())).thenReturn(reader);
        when(reader.hasMetadata()).thenReturn(true);
        when(reader.getMetadata()).thenReturn(SimulationMetadata.getDefaultInstance());
        when(reader.getOrganismTickRange()).thenReturn(new TickRange(0, 100));
        when(reader.readTotalOrganismsCreated(anyLong()))
            .thenAnswer(inv -> rows.isEmpty() ? 0 : rows.lastKey());
        when(reader.readParents(anyInt(), anyInt())).thenAnswer(inv -> {
            if (failure != null) {
                throw failure;
            }
            final int after = inv.getArgument(0);
            final int limit = inv.getArgument(1);
            onRead.accept(after);
            final List<Map.Entry<Integer, Integer>> page =
                rows.tailMap(after, false).entrySet().stream().limit(limit).toList();
            return new ParentRows(
                page.stream().mapToInt(Map.Entry::getKey).toArray(),
                page.stream().mapToInt(Map.Entry::getValue).toArray());
        });
        when(reader.readOrganismStaticInfo(anyInt())).thenAnswer(inv -> {
            final int id = inv.getArgument(0);
            if (!rows.containsKey(id)) {
                return null;
            }
            final int parent = rows.get(id);
            return new OrganismStaticInfo(parent == 0 ? null : parent, id * 10L, -1L, "prog",
                new int[]{id, id + 1}, 0L, 0, null);
        });
    }

    /**
     * The indexes of this run, reading the test's executor and clock.
     *
     * @param pageSize Rows per read
     * @return New indexes
     */
    AncestryIndexes indexes(final int pageSize) {
        return new AncestryIndexes(provider, executor, pageSize, () -> nanos, 2);
    }

    /**
     * A single index of this run whose stop signal is {@link #stopping}.
     *
     * @param pageSize Rows per read
     * @return A new index
     */
    AncestryIndex index(final int pageSize) {
        return new AncestryIndex(RUN_ID, provider, executor, pageSize, () -> nanos, () -> stopping);
    }

    /**
     * Describes a tick the way the controller does: the root's row first, then the answer.
     *
     * @param view      The request's view
     * @param root      The requested root
     * @param organisms The organisms of the tick
     * @param reader    The reader for the root's row
     * @return The answer
     * @throws Exception if the root is not indexed or a read fails
     */
    static DescentDto describe(final DescentQuery.View view, final RootRequest root,
                               final List<OrganismTickSummary> organisms, final IDatabaseReader reader)
            throws Exception {
        return view.describe(root, view.rootInfo(root, reader), organisms, reader);
    }

    /**
     * Makes the run a fork whose first recorded tick has created {@code boundary} organisms.
     */
    void forkedAt(final long firstTick, final int boundary) throws Exception {
        when(reader.getMetadata()).thenReturn(SimulationMetadata.newBuilder()
            .setFork(ForkOrigin.newBuilder().setParentRunId("parent").setFirstTick(firstTick).setLastTick(firstTick + 100))
            .build());
        when(reader.readTotalOrganismsCreated(firstTick)).thenReturn(boundary);
    }

    /**
     * Makes the run a fork whose first recorded tick is not indexed yet.
     */
    void forkedAtUnindexedTick(final long firstTick) throws Exception {
        when(reader.getMetadata()).thenReturn(SimulationMetadata.newBuilder()
            .setFork(ForkOrigin.newBuilder().setParentRunId("parent").setFirstTick(firstTick).setLastTick(firstTick + 100))
            .build());
        when(reader.readTotalOrganismsCreated(firstTick)).thenThrow(new TickNotFoundException("not indexed"));
    }

    /**
     * Adds organisms: pairs of id and parent, parent 0 for a founder.
     */
    FakeRun with(final int... idParentPairs) {
        for (int i = 0; i < idParentPairs.length; i += 2) {
            rows.put(idParentPairs[i], idParentPairs[i + 1]);
        }
        return this;
    }

    /**
     * The organisms of a tick: every id alive, except those listed as dead.
     */
    static List<OrganismTickSummary> tick(final int[] alive, final int... dead) {
        final java.util.ArrayList<OrganismTickSummary> list = new java.util.ArrayList<>();
        for (final int id : alive) {
            list.add(summary(id, false));
        }
        for (final int id : dead) {
            list.add(summary(id, true));
        }
        return list;
    }

    private static OrganismTickSummary summary(final int id, final boolean dead) {
        return new OrganismTickSummary(id, 100, new int[]{0, 0}, new int[]{1, 0}, new int[0][], 0,
            null, 0L, 0, 0L, dead, dead ? 1L : -1L);
    }

    /**
     * An executor that queues its tasks until {@link #runAll()}.
     */
    static final class ManualExecutor extends AbstractExecutorService {
        private final Queue<Runnable> queue = new ArrayDeque<>();
        private boolean shutdown;

        /** Runs every queued task, including tasks queued while running. */
        void runAll() {
            Runnable task;
            while ((task = queue.poll()) != null) {
                task.run();
            }
        }

        /** Number of queued tasks. */
        int queued() {
            return queue.size();
        }

        @Override
        public void execute(final Runnable command) {
            if (shutdown) {
                throw new java.util.concurrent.RejectedExecutionException("shut down");
            }
            queue.add(command);
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            final List<Runnable> dropped = List.copyOf(queue);
            queue.clear();
            return dropped;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(final long timeout, final TimeUnit unit) {
            return true;
        }
    }
}
