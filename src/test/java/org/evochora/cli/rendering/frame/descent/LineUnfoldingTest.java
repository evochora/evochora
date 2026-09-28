package org.evochora.cli.rendering.frame.descent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LineUnfolding}: the ends of the window, the stagger of the groups, the eased
 * curve and the colours the groups end in.
 */
@Tag("unit")
class LineUnfoldingTest {

    private static final int[] PALETTE = DescentRenderer.PALETTE;
    private static final int GREEN = PALETTE[0];
    private static final int MAGENTA = PALETTE[1];
    private static final int OTHER = DescentRenderer.OTHER_TONE;

    @Test
    void everyGroupStartsInTheFieldAndEndsInItsOwnColourWithinTheWindow() {
        final LineUnfolding unfolding = new LineUnfolding(90, false);

        for (int group = 0; group < 9; group++) {
            assertThat(unfolding.progress(group, 9, 0)).as("group %d at the jump", group).isZero();
            assertThat(unfolding.progress(group, 9, 90)).as("group %d at the end", group).isEqualTo(1.0);
        }
        assertThat(unfolding.progress(0, 9, 89)).as("the largest line has long arrived").isEqualTo(1.0);
        assertThat(unfolding.progress(8, 9, 89)).as("the last group arrives on the last frame")
            .isGreaterThan(0.99).isLessThan(1.0);
        assertThat(unfolding.colour(GREEN, MAGENTA, 1, 2, 0)).isEqualTo(GREEN);
        assertThat(unfolding.colour(GREEN, MAGENTA, 1, 2, 90)).isEqualTo(MAGENTA);
    }

    @Test
    void theLargestLineUnfoldsFirstAndTheSmallerOnesFollowInRankOrder() {
        final LineUnfolding unfolding = new LineUnfolding(90, false);

        // Three groups: each starts an eighth of the window after the one before
        assertThat(unfolding.progress(0, 3, 5)).isPositive();
        assertThat(unfolding.progress(1, 3, 11)).isZero();
        assertThat(unfolding.progress(1, 3, 12)).isPositive();
        assertThat(unfolding.progress(2, 3, 22)).isZero();
        for (int frame = 1; frame < 90; frame++) {
            assertThat(unfolding.progress(0, 3, frame)).isGreaterThanOrEqualTo(unfolding.progress(1, 3, frame));
            assertThat(unfolding.progress(1, 3, frame)).isGreaterThanOrEqualTo(unfolding.progress(2, 3, frame));
        }
    }

    @Test
    void manyGroupsShortenTheStaggerSoThatEachKeepsHalfTheWindow() {
        final LineUnfolding unfolding = new LineUnfolding(80, false);

        // Nine groups: the stagger drops from 10 frames to 40 / 8 = 5, each group takes 40 frames
        assertThat(unfolding.progress(8, 9, 40)).isZero();
        assertThat(unfolding.progress(8, 9, 60)).isCloseTo(0.5, within(1e-9));
        assertThat(unfolding.progress(0, 9, 20)).as("smoothstep is symmetric about its middle")
            .isCloseTo(0.5, within(1e-9));
        assertThat(unfolding.progress(0, 9, 10)).isCloseTo(0.15625, within(1e-9));
    }

    @Test
    void theGroupsTakeThePaletteInOrderAndTheLinesBeyondItTheOtherTone() {
        final int[] targets = new LineUnfolding(90, false).targets(PALETTE, OTHER, MAGENTA, 9);

        assertThat(targets).containsExactly(PALETTE[0], PALETTE[1], PALETTE[2], PALETTE[3], PALETTE[4],
            PALETTE[5], PALETTE[6], PALETTE[7], OTHER);
    }

    @Test
    void keepingTheFieldColourGivesItToTheLargestLineAndSkipsItForTheOthers() {
        final LineUnfolding keep = new LineUnfolding(90, true);

        assertThat(keep.targets(PALETTE, OTHER, MAGENTA, 4)).containsExactly(MAGENTA, GREEN, PALETTE[2], PALETTE[3]);
        assertThat(keep.targets(PALETTE, OTHER, MAGENTA, 9)).containsExactly(MAGENTA, GREEN, PALETTE[2], PALETTE[3],
            PALETTE[4], PALETTE[5], PALETTE[6], PALETTE[7], OTHER);
        assertThat(keep.targets(PALETTE, OTHER, OTHER, 3)).as("a field outside the palette")
            .containsExactly(OTHER, GREEN, MAGENTA);
        assertThat(keep.targets(PALETTE, OTHER, LineUnfolding.NO_FIELD, 2)).as("no field at the start")
            .containsExactly(GREEN, MAGENTA);
    }

    @Test
    void aWindowBelowOneFrameIsRejected() {
        assertThatThrownBy(() -> new LineUnfolding(0, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--unfold-frames");
    }

    @Test
    void mixesChannelByChannel() {
        assertThat(LineUnfolding.mix(0x000000, 0xff8040, 0.5)).isEqualTo(0x804020);
        assertThat(LineUnfolding.mix(GREEN, MAGENTA, 0.0)).isEqualTo(GREEN);
        assertThat(LineUnfolding.mix(GREEN, MAGENTA, 1.0)).isEqualTo(MAGENTA);
    }
}
