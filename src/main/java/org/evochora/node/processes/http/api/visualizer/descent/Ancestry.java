package org.evochora.node.processes.http.api.visualizer.descent;

import java.util.Arrays;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.ints.IntIterable;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

/**
 * The parent of every organism of one run as far as it is known, and the walks over it.
 * <p>
 * The array is indexed by organism id: the entry of an id is the id of its parent,
 * {@link #NO_PARENT} for a founder, and {@link #UNREAD} for an id whose parent is not known. Ids
 * grow with descent, so a parent's id is always below its child's. A view reads the entries up
 * to its limit and reports every id above it as {@link #UNREAD}.
 * <p>
 * Key features:
 * <ul>
 *   <li><em>Line of an organism</em> relative to a root: the child of the root it descends from,
 *       by {@link #lineOf}, with a memo and a path buffer that the caller keeps for as long as
 *       it asks about the same root.</li>
 *   <li><em>Tree of the living</em>: the ancestors of a set of living organisms with the children
 *       through which each of them leads to the living, by {@link #living}. It gives the common
 *       ancestor of the living, the landing of every line and the landing above a root.</li>
 *   <li><em>Sizes of the lines</em> of a root: every organism above the root attributed to its
 *       line, by a {@link LineCount}, which can be extended when the view grows.</li>
 * </ul>
 * <p>
 * The class holds data and walks only: it reads nothing, starts no threads and knows nothing of
 * colours.
 * <p>
 * <strong>Thread safety:</strong> a view never writes into its array and can be read from any
 * number of threads once it has been published safely. The array is not copied: whoever fills
 * it must have written every entry at or below the limit before the view is handed to another
 * thread (through a {@code volatile} field or a final field of an object published after it).
 * An owner that keeps writing may only enter ids above the limit, or turn an {@link #UNREAD}
 * entry at or below it into its parent; a reader then sees either value, both of which are
 * consistent answers. {@link Living} and {@link LineCount} are not thread-safe.
 */
public final class Ancestry {

    /** Value of an entry whose parent is not known. */
    public static final int UNREAD = -1;

    /** Value of an entry whose organism has no parent in the run. Never an organism id. */
    public static final int NO_PARENT = 0;

    /** Line value of an organism that does not descend from the root. */
    public static final int OUTSIDE = 0;

    /** Line value of an organism whose ancestry is not known. */
    public static final int UNKNOWN = -1;

    private final int[] parents;
    private final int limit;

    /**
     * Creates a view of an array of parents.
     *
     * @param parents The array, indexed by organism id; not copied (see the class comment)
     * @param limit   Highest id the view reads; entries above it are reported as {@link #UNREAD}
     *                (must be &gt;= 0)
     * @throws IllegalArgumentException if {@code limit} is negative
     * @throws NullPointerException     if {@code parents} is {@code null}
     */
    public Ancestry(final int[] parents, final int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative: " + limit);
        }
        this.parents = java.util.Objects.requireNonNull(parents, "parents");
        this.limit = limit;
    }

    /**
     * The highest id this view reads.
     *
     * @return The limit given at construction
     */
    public int limit() {
        return limit;
    }

    /**
     * The parent of an organism as far as this view knows it.
     *
     * @param id Organism id
     * @return The parent id, {@link #NO_PARENT} for a founder, {@link #UNREAD} for an id this
     *         view does not know, including every id &lt;= 0 and every id above the limit
     */
    public int parentOf(final int id) {
        if (id > limit || id >= parents.length || id <= 0) {
            return UNREAD;
        }
        return parents[id];
    }

    /**
     * Creates a memo for {@link #lineOf}: a map whose default return value is
     * {@link Integer#MIN_VALUE}.
     *
     * @return An empty memo
     */
    public static Int2IntOpenHashMap newMemo() {
        final Int2IntOpenHashMap memo = new Int2IntOpenHashMap();
        memo.defaultReturnValue(Integer.MIN_VALUE);
        return memo;
    }

    /**
     * The line an organism belongs to relative to a root: the child of the root it descends
     * from.
     * <p>
     * Walks the parents upward while the id is above the root; the last id before the root is
     * the line. The walk ends at once when the id drops below the root, since ids grow with
     * descent and nothing below the root descends from it. An unknown entry on the way makes the
     * organism unknown. Every id visited is entered into the memo with the same answer, so a set
     * of organisms costs one walk per distinct ancestor when the memo is shared.
     *
     * @param root The root, {@link #NO_PARENT} for the virtual root above the founders
     * @param id   Organism id (must be &gt; 0)
     * @param memo Answers known so far for this root and this view; filled by this call. Its
     *             default return value must be {@link Integer#MIN_VALUE} (see {@link #newMemo()})
     * @param path Buffer for the ids of one walk, reused across calls; cleared by this call
     * @return The line id; the root's own id for the root itself; {@link #OUTSIDE} for an
     *         organism not descended from the root; {@link #UNKNOWN} when the walk meets an
     *         unknown entry
     */
    public int lineOf(final int root, final int id, final Int2IntOpenHashMap memo, final IntArrayList path) {
        if (id == root) {
            return root;
        }
        path.clear();
        int x = id;
        int last = id;
        int result;
        while (true) {
            if (x <= root) {
                result = x == root ? last : OUTSIDE;
                break;
            }
            final int known = memo.get(x);
            if (known != Integer.MIN_VALUE) {
                result = known;
                break;
            }
            path.add(x);
            last = x;
            final int p = parentOf(x);
            if (p == UNREAD) {
                result = UNKNOWN;
                break;
            }
            x = p;
        }
        for (int i = 0; i < path.size(); i++) {
            memo.put(path.getInt(i), result);
        }
        return result;
    }

    /**
     * Builds the tree of a set of living organisms below a top node.
     * <p>
     * Every living organism whose ancestry is known walks upward until an ancestor already
     * visited or the top, recording for each ancestor the child through which it was reached.
     * An organism with an unknown ancestry, or one that does not descend from the top, is left
     * out and reported by {@link Living#unknownLiving()} or {@link Living#outsideTop()}.
     *
     * @param livingIds The living organisms, each id &gt; 0
     * @param top       The node the walks end at: {@link #NO_PARENT} for the virtual root above
     *                  the founders, or an organism known to be an ancestor of the living
     * @return The tree
     */
    public Living living(final IntIterable livingIds, final int top) {
        final Living tree = new Living(this, top);
        final Int2IntOpenHashMap memo = newMemo();
        final IntArrayList path = new IntArrayList();
        final IntOpenHashSet seen = new IntOpenHashSet();
        final IntIterator it = livingIds.iterator();
        while (it.hasNext()) {
            final int id = it.nextInt();
            final int line = lineOf(top, id, memo, path);
            if (line == UNKNOWN) {
                tree.unknownLiving = true;
                continue;
            }
            if (line == OUTSIDE && top != NO_PARENT) {
                tree.outsideTop = true;
                continue;
            }
            tree.living.add(id);
            int x = id;
            while (x != top && seen.add(x)) {
                final int p = parentOf(x);
                tree.children.computeIfAbsent(p, k -> new IntArrayList()).add(x);
                x = p;
            }
        }
        return tree;
    }

    /**
     * A landing: where a descent or an ascent through the tree of the living stops, and how many
     * generations it passed.
     *
     * @param root    The node the walk stopped at, {@link #NO_PARENT} for the virtual root
     * @param skipped Generations passed on the way
     */
    public record Landing(int root, int skipped) {}

    /**
     * The ancestors of a set of living organisms, with the children through which each of them
     * leads to the living.
     * <p>
     * <strong>Thread safety:</strong> not thread-safe; built and read by one thread.
     */
    public static final class Living {
        private final Ancestry ancestry;
        private final int top;
        private final IntOpenHashSet living = new IntOpenHashSet();
        private final Int2ObjectOpenHashMap<IntArrayList> children = new Int2ObjectOpenHashMap<>();
        private boolean unknownLiving;
        private boolean outsideTop;

        private Living(final Ancestry ancestry, final int top) {
            this.ancestry = ancestry;
            this.top = top;
        }

        /**
         * Whether a living organism has an ancestry that is not known, and is therefore missing
         * from the tree.
         *
         * @return {@code true} if one was left out
         */
        public boolean unknownLiving() {
            return unknownLiving;
        }

        /**
         * The living organisms the tree holds: those whose ancestry is known and that descend
         * from the top.
         *
         * @return The count
         */
        public int knownLiving() {
            return living.size();
        }

        /**
         * Whether a living organism does not descend from the top the tree was built below, and
         * is therefore missing from it. Never the case for the top {@link #NO_PARENT}.
         *
         * @return {@code true} if one was left out
         */
        public boolean outsideTop() {
            return outsideTop;
        }

        /**
         * Whether an organism is one of the living in the tree.
         *
         * @param id Organism id
         * @return {@code true} if it was given as living and its ancestry is known
         */
        public boolean isLiving(final int id) {
            return living.contains(id);
        }

        /**
         * The number of children through which a node leads to the living.
         *
         * @param id Organism id, {@link #NO_PARENT} for the virtual root
         * @return The number of such children, 0 for a node outside the tree
         */
        public int childCount(final int id) {
            final IntArrayList list = children.get(id);
            return list == null ? 0 : list.size();
        }

        /**
         * The youngest common ancestor of the living: the descent from the top.
         *
         * @return The common ancestor, {@link #NO_PARENT} when the living below the virtual root
         *         have none; the top itself when there are no living
         */
        public int commonAncestor() {
            return down(top).root();
        }

        /**
         * Descends from a node while it is not alive itself and leads to the living through
         * exactly one child.
         *
         * @param start The node to start at, {@link #NO_PARENT} for the virtual root
         * @return Where the descent stops and how many generations it passed
         */
        public Landing down(final int start) {
            int x = start;
            int skipped = 0;
            while (!living.contains(x)) {
                final IntArrayList list = children.get(x);
                if (list == null || list.size() != 1) {
                    break;
                }
                x = list.getInt(0);
                skipped++;
            }
            return new Landing(x, skipped);
        }

        /**
         * Ascends from a node while it is not alive itself and leads to the living through fewer
         * than two children, ending at the virtual root at the latest.
         *
         * @param start The node to start at, typically the parent of a root
         * @return Where the ascent stops and how many generations it passed; {@code null} when an
         *         ancestor on the way is not known
         */
        public Landing up(final int start) {
            int x = start;
            int skipped = 0;
            while (x != NO_PARENT && !living.contains(x) && childCount(x) < 2) {
                final int above = ancestry.parentOf(x);
                if (above == UNREAD) {
                    return null;
                }
                x = above;
                skipped++;
            }
            return new Landing(x, skipped);
        }
    }

    /**
     * The sizes of the lines of one root, immutable once made.
     *
     * @param ids   Line ids, largest line first, ties by ascending id
     * @param sizes Members of each line, the line's child included, parallel to {@code ids}
     * @param total Members of all lines together: the root's descendants
     */
    public record LineSizes(int[] ids, int[] sizes, long total) {}

    /**
     * Counts the members of every line of one root.
     * <p>
     * Every organism above the root is attributed to its line from the line of its parent, read
     * before it, since a parent's id is below its child's. The attribution is kept, so that a
     * view with a higher limit costs only its new ids ({@link #extend}). An entry that went from
     * {@link #UNREAD} to its parent below the ids already covered is not seen by an extension;
     * after such a change the count has to start again from a new instance.
     * <p>
     * Heap: four bytes per id above the root, for as long as the instance is kept.
     * <p>
     * <strong>Thread safety:</strong> not thread-safe; the caller guards an instance it shares.
     */
    public static final class LineCount {
        private final int root;
        private int covered;
        private int[] line;
        private final Int2IntOpenHashMap counts = new Int2IntOpenHashMap();
        private long total;

        /**
         * Creates a count that covers nothing yet.
         *
         * @param root The root, {@link #NO_PARENT} for the virtual root above the founders
         */
        public LineCount(final int root) {
            this.root = root;
            this.covered = root;
            this.line = new int[0];
        }

        /**
         * Attributes the ids above what is covered, up to the view's limit.
         *
         * @param ancestry The view to read; its limit must not be below that of the views
         *                 given before
         * @return This count
         */
        public LineCount extend(final Ancestry ancestry) {
            final int[] parents = ancestry.parents;
            final int last = Math.min(ancestry.limit, parents.length - 1);
            if (last > covered) {
                if (last - root + 1 > line.length) {
                    line = Arrays.copyOf(line, Math.max(last - root + 1, line.length + (line.length >> 1)));
                }
                for (int id = covered + 1; id <= last; id++) {
                    final int p = parents[id];
                    final int l;
                    if (p == UNREAD) {
                        l = UNKNOWN;
                    } else if (p == root) {
                        l = id;
                    } else if (p < root) {
                        l = OUTSIDE;
                    } else {
                        l = line[p - root];
                    }
                    line[id - root] = l;
                    if (l > 0) {
                        counts.addTo(l, 1);
                        total++;
                    }
                }
                covered = last;
            }
            return this;
        }

        /**
         * The sizes as counted so far, largest line first.
         *
         * @return New arrays, owned by the caller
         */
        public LineSizes sizes() {
            final int[] ids = counts.keySet().toIntArray();
            IntArrays.quickSort(ids, (a, b) -> {
                final int bySize = Integer.compare(counts.get(b), counts.get(a));
                return bySize != 0 ? bySize : Integer.compare(a, b);
            });
            final int[] sizes = new int[ids.length];
            for (int i = 0; i < ids.length; i++) {
                sizes[i] = counts.get(ids[i]);
            }
            return new LineSizes(ids, sizes, total);
        }
    }
}
