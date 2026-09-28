package org.evochora.node.processes.http.api.visualizer.descent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;

/**
 * Tests for {@link Ancestry}: the line walk, the tree of the living with its common ancestor and
 * landings, and the line sizes.
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
class AncestryTest {

    /** Parents of the family above, indexed by id; 0 is unused. */
    private static final int[] FAMILY = {Ancestry.UNREAD, 0, 1, 1, 2, 4, 0, 4};

    private static Ancestry family() {
        return new Ancestry(FAMILY, 7);
    }

    private static IntList ids(final int... ids) {
        return IntArrayList.wrap(ids);
    }

    @Test
    void walksALineUpToTheChildOfTheRoot() {
        final Ancestry ancestry = family();

        final Int2IntOpenHashMap memo = Ancestry.newMemo();
        assertThat(ancestry.lineOf(1, 5, memo, new IntArrayList())).isEqualTo(2);
        assertThat(memo).containsEntry(4, 2).containsEntry(2, 2);
        assertThat(ancestry.lineOf(1, 4, memo, new IntArrayList())).isEqualTo(2);
        assertThat(ancestry.lineOf(1, 3, memo, new IntArrayList())).isEqualTo(3);
        assertThat(ancestry.lineOf(1, 6, memo, new IntArrayList())).isEqualTo(Ancestry.OUTSIDE);
        assertThat(ancestry.lineOf(1, 1, memo, new IntArrayList())).as("the root itself").isEqualTo(1);
        // Below the root the walk ends at once
        assertThat(ancestry.lineOf(4, 3, Ancestry.newMemo(), new IntArrayList())).isEqualTo(Ancestry.OUTSIDE);

        final Int2IntOpenHashMap all = Ancestry.newMemo();
        assertThat(ancestry.lineOf(0, 5, all, new IntArrayList())).isEqualTo(1);
        assertThat(ancestry.lineOf(0, 6, all, new IntArrayList())).isEqualTo(6);
    }

    @Test
    void anIdAboveTheLimitOrAnUnreadParentMakesTheLineUnknown() {
        final Ancestry upToFour = new Ancestry(FAMILY, 4);
        assertThat(upToFour.parentOf(5)).isEqualTo(Ancestry.UNREAD);
        assertThat(upToFour.parentOf(0)).isEqualTo(Ancestry.UNREAD);
        assertThat(upToFour.lineOf(0, 5, Ancestry.newMemo(), new IntArrayList())).isEqualTo(Ancestry.UNKNOWN);

        final int[] gap = FAMILY.clone();
        gap[2] = Ancestry.UNREAD;
        assertThat(new Ancestry(gap, 7).lineOf(0, 5, Ancestry.newMemo(), new IntArrayList()))
            .isEqualTo(Ancestry.UNKNOWN);
        assertThatThrownBy(() -> new Ancestry(FAMILY, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theCommonAncestorOfTheLivingIgnoresNothingButTheDead() {
        final Ancestry ancestry = family();

        assertThat(ancestry.living(ids(5, 3), 0).commonAncestor()).isEqualTo(1);
        assertThat(ancestry.living(ids(5, 7), 0).commonAncestor()).isEqualTo(4);
        assertThat(ancestry.living(ids(4, 5), 0).commonAncestor())
            .as("a living ancestor of the living is their common ancestor").isEqualTo(4);
        assertThat(ancestry.living(ids(5, 6), 0).commonAncestor()).as("two founder lines").isEqualTo(0);
        assertThat(ancestry.living(ids(), 0).commonAncestor()).as("nobody alive").isEqualTo(0);
    }

    @Test
    void aTopBelowTheFoundersGivesTheSameCommonAncestor() {
        final Ancestry ancestry = family();

        final Ancestry.Living tree = ancestry.living(ids(5, 7), 1);
        assertThat(tree.commonAncestor()).isEqualTo(4);
        assertThat(tree.outsideTop()).isFalse();
        assertThat(ancestry.living(ids(1), 1).commonAncestor()).as("the top itself alive").isEqualTo(1);

        final Ancestry.Living outside = ancestry.living(ids(5, 6), 1);
        assertThat(outside.outsideTop()).as("6 does not descend from 1").isTrue();
        assertThat(outside.isLiving(6)).isFalse();
        assertThat(outside.commonAncestor()).isEqualTo(5);
    }

    @Test
    void landsDownWhereTheLivingSplitAndUpWhereTheLivingSplitAboveARoot() {
        final Ancestry.Living tree = family().living(ids(5, 7, 3), 0);

        assertThat(tree.down(2)).as("2 leads to the living through 4 alone").isEqualTo(new Ancestry.Landing(4, 1));
        assertThat(tree.down(3)).isEqualTo(new Ancestry.Landing(3, 0));
        assertThat(tree.childCount(4)).isEqualTo(2);
        // Above the root 4: its parent 2, then 1 where the living split
        assertThat(tree.up(2)).isEqualTo(new Ancestry.Landing(1, 1));
        assertThat(tree.up(0)).isEqualTo(new Ancestry.Landing(0, 0));
    }

    @Test
    void anUnknownLivingOrganismIsLeftOutAndTheWayUpThroughAnUnknownAncestorHasNoLanding() {
        final int[] gap = FAMILY.clone();
        gap[2] = Ancestry.UNREAD;
        final Ancestry ancestry = new Ancestry(gap, 7);

        final Ancestry.Living tree = ancestry.living(ids(5, 3), 0);
        assertThat(tree.unknownLiving()).isTrue();
        assertThat(tree.isLiving(5)).isFalse();
        assertThat(tree.commonAncestor()).isEqualTo(3);
        assertThat(ancestry.living(ids(3), 0).up(4)).isNull();
    }

    @Test
    void countsTheLinesOfARootLargestFirst() {
        final Ancestry.LineSizes one = new Ancestry.LineCount(1).extend(family()).sizes();
        assertThat(one.ids()).containsExactly(2, 3);
        assertThat(one.sizes()).containsExactly(4, 1);
        assertThat(one.total()).isEqualTo(5);

        final Ancestry.LineSizes all = new Ancestry.LineCount(0).extend(family()).sizes();
        assertThat(all.ids()).containsExactly(1, 6);
        assertThat(all.sizes()).containsExactly(6, 1);
    }

    @Test
    void anExtendedCountEqualsACountFromScratch() {
        final int[] grown = Arrays.copyOf(FAMILY, 10);
        grown[8] = 3;
        grown[9] = 8;
        final Ancestry.LineCount count = new Ancestry.LineCount(1).extend(new Ancestry(grown, 7));
        assertThat(count.sizes().sizes()).containsExactly(4, 1);

        final Ancestry.LineSizes extended = count.extend(new Ancestry(grown, 9)).sizes();
        final Ancestry.LineSizes fresh = new Ancestry.LineCount(1).extend(new Ancestry(grown, 9)).sizes();

        assertThat(extended.ids()).containsExactly(fresh.ids());
        assertThat(extended.sizes()).containsExactly(fresh.sizes());
        assertThat(extended.sizes()).containsExactly(4, 3);
        assertThat(extended.total()).isEqualTo(7);
    }
}
