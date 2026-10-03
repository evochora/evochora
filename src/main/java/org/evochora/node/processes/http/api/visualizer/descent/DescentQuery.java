package org.evochora.node.processes.http.api.visualizer.descent;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.resources.database.IOrganismDataReader;
import org.evochora.datapipeline.api.resources.database.OrganismNotFoundException;
import org.evochora.datapipeline.api.resources.database.dto.OrganismStaticInfo;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickSummary;
import org.evochora.node.processes.http.api.visualizer.dto.DescentDto;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * Answers how the organisms of a tick descend from a root, from the {@link AncestryIndex} of one
 * run and without any SQL but the one row of the root. The walks are those of {@link Ancestry},
 * on the view an {@link AncestryIndex.Snapshot} carries.
 * <p>
 * Key features:
 * <ul>
 *   <li><em>Line of an organism</em>: the child of the root it descends from, by
 *       {@link Ancestry#lineOf}, with a memo and a path buffer per request that are discarded
 *       with the request.</li>
 *   <li><em>Tree of the living</em>: by {@link Ancestry#living}. It gives the common ancestor of
 *       the living (descending from {@code all} while there is exactly one child and the node is
 *       not itself alive), the landing of every line (the same descent from the line's child) and
 *       the landing above the root (ascending until an ancestor that is alive or has two or more
 *       children, else {@code all}).</li>
 *   <li><em>Sizes of the lines</em>: by an {@link Ancestry.LineCount}. The count of the most
 *       recently used roots is kept with its attribution array, so a page appended to the index
 *       costs only its new ids; a filled gap can change the line of organisms counted before and
 *       makes the next request count again from scratch. The sizes and their order are kept per
 *       root and version, so repeated requests on an unchanged index count nothing.</li>
 * </ul>
 * <p>
 * Heap: the attribution array of a root takes four bytes per organism above it; it is kept for as
 * many of the most recently used roots per run as the controller's configuration allows, the
 * sizes alone for up to {@value #SIZE_CACHE_ROOTS} roots.
 * <p>
 * <strong>Thread safety:</strong> thread-safe. A request works on a {@link View} of its own; the
 * size cache is guarded by this object's monitor, and what leaves it is immutable.
 */
public final class DescentQuery {

    /** Number of lines that get a colour rank. */
    static final int COLOURED_LINES = 8;

    /** Roots whose line sizes are kept. */
    static final int SIZE_CACHE_ROOTS = 32;

    private final AncestryIndex index;
    private final int attributionArrays;

    // Guarded by this object's monitor; access order, the eldest first
    private final LinkedHashMap<Integer, RootSizes> sizeCache = new LinkedHashMap<>(16, 0.75f, true);

    /** Full counts done so far, for tests. */
    private long fullCounts;

    /**
     * Binds the queries to the index of one run.
     *
     * @param index             The run's index
     * @param attributionArrays Roots whose attribution array is kept for incremental counting
     *                          (must be &gt;= 0; 0 counts every new version from scratch)
     * @throws IllegalArgumentException if {@code attributionArrays} is negative
     */
    DescentQuery(final AncestryIndex index, final int attributionArrays) {
        if (attributionArrays < 0) {
            throw new IllegalArgumentException("attributionArrays must not be negative: " + attributionArrays);
        }
        this.index = index;
        this.attributionArrays = attributionArrays;
    }

    /**
     * Roots whose attribution array is kept for incremental counting.
     *
     * @return The configured number
     */
    int attributionArrays() {
        return attributionArrays;
    }

    /**
     * The index the queries read.
     *
     * @return The run's index
     */
    AncestryIndex index() {
        return index;
    }

    /**
     * Takes a view of the index for one request and asks for a catch-up if the index lags behind
     * the tick. The view is what the answer and its cache key are built from.
     *
     * @param tickTotal Organisms created up to the requested tick
     * @return The view
     */
    public View view(final int tickTotal) {
        final AncestryIndex.Snapshot snapshot = index.snapshot();
        index.requestCatchUp(tickTotal);
        return new View(snapshot, snapshot.stateFor(tickTotal), index.progressOf(snapshot, tickTotal),
            index.newestTotal());
    }

    /**
     * The index as one request sees it.
     * <p>
     * Not shared between requests; it holds nothing that another thread changes.
     */
    public final class View {
        private final AncestryIndex.Snapshot snapshot;
        private final AncestryIndex.State state;
        private final double progress;
        private final int newestTotal;
        private final int organismsInRun;

        private View(final AncestryIndex.Snapshot snapshot, final AncestryIndex.State state,
                     final double progress, final int newestTotal) {
            this.snapshot = snapshot;
            this.state = state;
            this.progress = progress;
            this.newestTotal = newestTotal;
            this.organismsInRun = organismsInRun(snapshot, newestTotal);
        }

        /**
         * The state of the index for the requested tick.
         *
         * @return The state
         */
        public AncestryIndex.State state() {
            return state;
        }

        /**
         * The version of the index this view shows.
         *
         * @return The version counter
         */
        public long version() {
            return snapshot.version();
        }

        /**
         * Reads the row of a root given by id, so that an id that is not indexed is reported
         * before anything else, whatever the state of the index.
         *
         * @param root   The requested root
         * @param reader The request's reader
         * @return The root's row; {@code null} for {@code all} and {@code auto}
         * @throws SQLException              if reading the row fails
         * @throws OrganismNotFoundException if the root is not indexed
         */
        public OrganismStaticInfo rootInfo(final RootRequest root, final IOrganismDataReader reader)
                throws SQLException, OrganismNotFoundException {
            return root.kind() == RootRequest.Kind.ORGANISM ? readRoot(reader, root.id()) : null;
        }

        /**
         * The part of a cache key that the descent adds: everything an answer for this view and
         * root depends on besides run and tick.
         *
         * @param root           The requested root
         * @param rootDeathTick  The death tick of the root the answer names, {@code null} for
         *                       {@code all} or an unresolved {@code auto}
         * @return The key part, starting with an underscore
         */
        public String cacheKey(final RootRequest root, final Long rootDeathTick) {
            return "_" + root.token() + "_" + state.wireName() + "_" + snapshot.version()
                + "_" + snapshot.cursor() + "_" + newestTotal + "_" + (rootDeathTick == null ? "-" : rootDeathTick);
        }

        /**
         * Describes how the organisms of the tick descend from the requested root.
         * <p>
         * While the index is not ready the answer carries the state, the progress, the organisms
         * in the run as far as they are known, and the root, but no lines; an {@code auto} root is then {@code null}, because it cannot be resolved
         * yet. It is also {@code null}, with no lines, when a living organism of the tick has an
         * unknown ancestry, since the common ancestor would then be resolved without it. An
         * organism of the tick whose ancestry turns out unknown asks for a catch-up that re-reads
         * the gaps.
         *
         * @param root      The requested root
         * @param rootInfo  The root's row as {@link #rootInfo} returned it
         * @param organisms The organisms of the tick
         * @param reader    The request's reader, for the row of a resolved {@code auto} root
         * @return The answer
         * @throws SQLException              if reading the root's row fails
         * @throws OrganismNotFoundException if a resolved root is not indexed
         */
        public DescentDto describe(final RootRequest root, final OrganismStaticInfo rootInfo,
                                   final List<OrganismTickSummary> organisms, final IOrganismDataReader reader)
                throws SQLException, OrganismNotFoundException {
            final String error = state == AncestryIndex.State.FAILED ? describeFailure(snapshot.failure()) : null;
            if (state != AncestryIndex.State.READY) {
                return notReady(root, rootInfo, error);
            }

            final Ancestry ancestry = snapshot.ancestry();
            final IntArrayList living = new IntArrayList();
            for (final OrganismTickSummary organism : organisms) {
                if (!organism.isDead) {
                    living.add(organism.organismId);
                }
            }
            final Ancestry.Living tree = ancestry.living(living, Ancestry.NO_PARENT);
            if (tree.unknownLiving()) {
                index.requestGapReread();
            }
            if (root.kind() == RootRequest.Kind.AUTO && tree.unknownLiving() && tree.knownLiving() == 0) {
                // No living organism to resolve the root over; every one of them is unread
                return new DescentDto(state.wireName(), progress, organismsInRun, null, null, List.of(), null,
                    Map.of(), living.size());
            }
            final int rootId = switch (root.kind()) {
                case ALL -> 0;
                case ORGANISM -> root.id();
                case AUTO -> tree.commonAncestor();
            };
            final OrganismStaticInfo info = root.kind() == RootRequest.Kind.AUTO && rootId != 0
                ? readRoot(reader, rootId) : rootInfo;

            final LineSizes sizes = sizesOf(snapshot, rootId);
            final Int2IntOpenHashMap memo = Ancestry.newMemo();
            final IntArrayList path = new IntArrayList();
            final Map<Integer, Integer> lineOf = new LinkedHashMap<>();
            final Int2IntOpenHashMap livingPerLine = new Int2IntOpenHashMap();
            final Int2IntOpenHashMap memoOfAll = Ancestry.newMemo();
            boolean unknownMet = false;
            int unreadLiving = 0;
            for (final OrganismTickSummary organism : organisms) {
                int line = ancestry.lineOf(rootId, organism.organismId, memo, path);
                if (line == Ancestry.OUTSIDE && !organism.isDead
                        && ancestry.lineOf(Ancestry.NO_PARENT, organism.organismId, memoOfAll, path) == Ancestry.UNKNOWN) {
                    // Below the root by its id, but whether it descends from anything is not known:
                    // its gap, not the root, keeps it out of the lines
                    line = Ancestry.UNKNOWN;
                }
                lineOf.put(organism.organismId, line);
                unknownMet |= line == Ancestry.UNKNOWN;
                if (!organism.isDead && line == Ancestry.UNKNOWN) {
                    unreadLiving++;
                }
                if (!organism.isDead && line > 0 && organism.organismId != rootId) {
                    livingPerLine.addTo(line, 1);
                }
            }
            if (unknownMet) {
                // A gap below the cursor: its rows may have been indexed since the index passed it
                index.requestGapReread();
            }

            final DescentDto.Root rootDto = rootId == 0
                ? new DescentDto.Root(0, null, null, null, sizes.total())
                : rootDto(rootId, info, sizes.total());
            return new DescentDto(state.wireName(), progress, organismsInRun, null, rootDto,
                lines(sizes, livingPerLine, tree), up(rootId, tree), lineOf, unreadLiving);
        }

        private DescentDto notReady(final RootRequest root, final OrganismStaticInfo rootInfo, final String error) {
            final DescentDto.Root rootDto = switch (root.kind()) {
                case ALL -> new DescentDto.Root(0, null, null, null, null);
                case ORGANISM -> rootDto(root.id(), rootInfo, null);
                case AUTO -> null;
            };
            return new DescentDto(state.wireName(), progress, organismsInRun, error, rootDto, List.of(), null,
                Map.of(), 0);
        }

        private List<DescentDto.Line> lines(final LineSizes sizes, final Int2IntOpenHashMap livingPerLine,
                                            final Ancestry.Living tree) {
            final int[] ids = sizes.ids();
            final List<DescentDto.Line> lines = new ArrayList<>(ids.length);
            for (int rank = 0; rank < ids.length; rank++) {
                final int id = ids[rank];
                final int living = livingPerLine.get(id);
                DescentDto.Landing landing = null;
                if (living > 0) {
                    final Ancestry.Landing down = tree.down(id);
                    if (down.skipped() > 0) {
                        landing = new DescentDto.Landing(down.root(), down.skipped());
                    }
                }
                lines.add(new DescentDto.Line(id, sizes.sizes()[rank],
                    rank < COLOURED_LINES ? Integer.valueOf(rank) : null, living, landing));
            }
            return lines;
        }

        private DescentDto.Up up(final int rootId, final Ancestry.Living tree) {
            if (rootId == 0) {
                return null;
            }
            final int parent = snapshot.parentOf(rootId);
            if (parent == Ancestry.UNREAD) {
                return null;
            }
            // No landing to offer when an ancestor on the way is not known
            final Ancestry.Landing landing = tree.up(parent);
            return new DescentDto.Up(parent,
                landing == null ? null : new DescentDto.Landing(landing.root(), landing.skipped()));
        }
    }

    /**
     * The sizes of the lines of a root at a snapshot, from the cache where it can.
     * <p>
     * An entry of the same version is returned as it is. An entry of the same gap-fill count
     * that still holds its attribution array is extended by the ids the snapshot adds. Anything
     * else is counted from scratch. An entry replaces the cached one only when it is newer: a
     * snapshot older than the cached entry is counted for itself and leaves the cache as it is,
     * so that the sizes of an answer belong to the snapshot the rest of the answer is made from.
     */
    synchronized LineSizes sizesOf(final AncestryIndex.Snapshot snapshot, final int root) {
        final RootSizes cached = sizeCache.get(root);
        if (cached != null && cached.result.version() == snapshot.version()) {
            return cached.result;
        }
        if (cached != null && cached.result.version() > snapshot.version()) {
            fullCounts++;
            return RootSizes.count(snapshot, root).result;
        }
        final RootSizes updated;
        if (cached != null && cached.count != null && cached.gapFills == snapshot.gapFills()) {
            updated = cached;
            updated.extend(snapshot);
        } else {
            updated = RootSizes.count(snapshot, root);
            fullCounts++;
        }
        sizeCache.put(root, updated);
        trimCache();
        return updated.result;
    }

    /**
     * Number of times the sizes of a root were counted from scratch.
     *
     * @return The count
     */
    synchronized long fullCounts() {
        return fullCounts;
    }

    private void trimCache() {
        while (sizeCache.size() > SIZE_CACHE_ROOTS) {
            final Iterator<Integer> eldest = sizeCache.keySet().iterator();
            eldest.next();
            eldest.remove();
        }
        // Only the most recently used roots keep their attribution array
        int kept = 0;
        final List<RootSizes> byRecency = new ArrayList<>(sizeCache.values());
        for (int i = byRecency.size() - 1; i >= 0; i--) {
            final RootSizes entry = byRecency.get(i);
            if (entry.count == null) {
                continue;
            }
            if (kept < attributionArrays) {
                kept++;
            } else {
                entry.count = null;
            }
        }
    }

    /**
     * The sizes of the lines of one root at one version of the index, immutable.
     *
     * @param version The index version the sizes were taken at
     * @param ids     Line ids, largest line first, ties by ascending id
     * @param sizes   Members of each line, the line's child included, parallel to {@code ids}
     * @param total   Members of all lines together: the root's descendants
     */
    record LineSizes(long version, int[] ids, int[] sizes, long total) {}

    /**
     * The mutable counting state of one root, guarded by the monitor of its {@link DescentQuery}.
     * The count is dropped, and with it its attribution array, when the root falls out of the
     * roots that keep one.
     */
    private static final class RootSizes {
        private long gapFills;
        private Ancestry.LineCount count;
        private LineSizes result;

        private RootSizes(final int root) {
            this.count = new Ancestry.LineCount(root);
        }

        static RootSizes count(final AncestryIndex.Snapshot snapshot, final int root) {
            final RootSizes sizes = new RootSizes(root);
            sizes.extend(snapshot);
            return sizes;
        }

        /**
         * Attributes the ids above what is covered, up to the snapshot's cursor, and refreshes
         * the result.
         */
        void extend(final AncestryIndex.Snapshot snapshot) {
            gapFills = snapshot.gapFills();
            final Ancestry.LineSizes sizes = count.extend(snapshot.ancestry()).sizes();
            result = new LineSizes(snapshot.version(), sizes.ids(), sizes.sizes(), sizes.total());
        }
    }

    /**
     * The organisms of this run as the index knows them: the larger of the newest tick's total
     * and the cursor, less the boundary. A fork's ids at or below the boundary were created in
     * its parent run and do not count; the cursor can be ahead of the newest total, which is read
     * before the pages of a catch-up.
     *
     * @param snapshot    The view of the index
     * @param newestTotal Organisms created up to the run's newest tick, 0 while not known
     * @return The count, 0 while the boundary is not known
     */
    static int organismsInRun(final AncestryIndex.Snapshot snapshot, final int newestTotal) {
        final int b = snapshot.boundary();
        if (b < 0) {
            return 0;
        }
        return Math.max(0, Math.max(newestTotal, snapshot.cursor()) - b);
    }

    private static OrganismStaticInfo readRoot(final IOrganismDataReader reader, final int id)
            throws SQLException, OrganismNotFoundException {
        final OrganismStaticInfo info = reader.readOrganismStaticInfo(id);
        if (info == null) {
            throw new OrganismNotFoundException("No organism metadata for root id " + id);
        }
        return info;
    }

    private static DescentDto.Root rootDto(final int id, final OrganismStaticInfo info, final Long descendants) {
        return new DescentDto.Root(id, info.birthTick, info.deathTick, info.initialPosition, descendants);
    }

    private static String describeFailure(final Throwable failure) {
        return failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }
}
