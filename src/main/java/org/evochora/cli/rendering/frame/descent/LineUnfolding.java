package org.evochora.cli.rendering.frame.descent;

/**
 * The transition after a root jump: every line of the new root starts in the colour of the field
 * it lies in and unfolds into its own colour.
 * <p>
 * When the root moves, the whole population is one line of the old root, one colour: the field.
 * The lines of the new root all lie inside it. Over a window of {@link #frames()} frames each
 * line's colour runs from the field to its own, the largest line first and every smaller one a
 * fixed stagger behind the one before, each along an eased curve (smoothstep: slow in, slow
 * out). The lines are counted in groups: one group per coloured line in rank order, and one for
 * all lines beyond the palette, which unfold together into the tone of other lines.
 * <p>
 * Parameters:
 * <ul>
 *   <li>{@code frames}: the window; the last group reaches its colour on its last frame.</li>
 *   <li>{@code keepFieldColour}: the largest line keeps the field's colour instead of taking the
 *       first palette colour; the other lines take the palette without that colour.</li>
 *   <li>{@code staggerPerGroup}: the delay of each group behind the one before, as a share of the
 *       window; lowered where needed so that every group still has {@code minDuration}.</li>
 *   <li>{@code minDuration}: the least share of the window a group's own unfolding takes.</li>
 * </ul>
 * The class holds the transition alone: the renderer decides when a window starts, which colour
 * the field has and what the palette is, so that a variant of the transition is a variant of
 * this class and nothing else.
 * <p>
 * <strong>Thread Safety:</strong> immutable and thread-safe.
 */
final class LineUnfolding {

    /** The window in frames when nothing else is given: 1.5 s at 60 fps. */
    static final int DEFAULT_FRAMES = 90;

    /** Delay of each group behind the one before, as a share of the window. */
    static final double DEFAULT_STAGGER_PER_GROUP = 1.0 / 8;

    /** Least share of the window a group's own unfolding takes. */
    static final double DEFAULT_MIN_DURATION = 0.5;

    /** Field value of a segment that starts without a field, at the start of the video. */
    static final int NO_FIELD = -1;

    private final int frames;
    private final boolean keepFieldColour;
    private final double staggerPerGroup;
    private final double minDuration;

    /**
     * Creates a transition with the default stagger and duration.
     *
     * @param frames          The window in frames (must be &gt;= 1)
     * @param keepFieldColour Whether the largest line keeps the field's colour
     * @throws IllegalArgumentException if {@code frames} is below 1
     */
    LineUnfolding(final int frames, final boolean keepFieldColour) {
        this(frames, keepFieldColour, DEFAULT_STAGGER_PER_GROUP, DEFAULT_MIN_DURATION);
    }

    /**
     * Creates a transition.
     *
     * @param frames          The window in frames (must be &gt;= 1)
     * @param keepFieldColour Whether the largest line keeps the field's colour
     * @param staggerPerGroup Delay of each group behind the one before, as a share of the window
     *                        (must be in [0, 1])
     * @param minDuration     Least share of the window a group's own unfolding takes (must be in
     *                        (0, 1])
     * @throws IllegalArgumentException if a parameter lies outside its range
     */
    LineUnfolding(final int frames, final boolean keepFieldColour, final double staggerPerGroup,
                  final double minDuration) {
        if (frames < 1) {
            throw new IllegalArgumentException("--unfold-frames must be 1 or greater, got " + frames);
        }
        if (!(staggerPerGroup >= 0 && staggerPerGroup <= 1)) {
            throw new IllegalArgumentException("staggerPerGroup must be in [0, 1], got " + staggerPerGroup);
        }
        if (!(minDuration > 0 && minDuration <= 1)) {
            throw new IllegalArgumentException("minDuration must be in (0, 1], got " + minDuration);
        }
        this.frames = frames;
        this.keepFieldColour = keepFieldColour;
        this.staggerPerGroup = staggerPerGroup;
        this.minDuration = minDuration;
    }

    /**
     * The window in frames.
     *
     * @return The number of frames from the jump to the frame on which every line has its colour
     */
    int frames() {
        return frames;
    }

    /**
     * The colour every group ends in.
     *
     * @param palette   The line colours in the order the lines take them
     * @param otherTone The colour of the lines beyond the palette
     * @param field     The field's colour, {@link #NO_FIELD} when the lines start without one
     * @param groups    Number of groups (must be &gt;= 0 and at most {@code palette.length + 1})
     * @return The colour of each group; group {@code palette.length} is the tone of other lines
     */
    int[] targets(final int[] palette, final int otherTone, final int field, final int groups) {
        final int[] targets = new int[groups];
        final boolean keep = keepFieldColour && field != NO_FIELD;
        int next = 0;
        for (int group = 0; group < groups; group++) {
            if (group >= palette.length) {
                targets[group] = otherTone;
            } else if (keep && group == 0) {
                targets[group] = field;
            } else {
                if (keep && next < palette.length && palette[next] == field) {
                    next++;
                }
                targets[group] = next < palette.length ? palette[next] : otherTone;
                next++;
            }
        }
        return targets;
    }

    /**
     * How far a group has unfolded at a frame of the window.
     *
     * @param group  The group, 0 for the largest line
     * @param groups Number of groups (must be &gt; {@code group})
     * @param frame  Frames since the jump, 0 on the frame of the jump
     * @return 0 for the field's colour, 1 for the group's own colour, eased in between
     */
    double progress(final int group, final int groups, final long frame) {
        if (frame >= frames) {
            return 1.0;
        }
        if (frame <= 0) {
            return 0.0;
        }
        final int last = groups - 1;
        final double stagger = last > 0
            ? Math.min(frames * staggerPerGroup, frames * (1.0 - minDuration) / last)
            : 0.0;
        final double duration = frames - last * stagger;
        final double t = Math.max(0.0, Math.min(1.0, (frame - group * stagger) / duration));
        return t * t * (3.0 - 2.0 * t);
    }

    /**
     * The colour of a group at a frame of the window.
     *
     * @param field  The field's colour (0xRRGGBB)
     * @param target The group's own colour (0xRRGGBB)
     * @param group  The group, 0 for the largest line
     * @param groups Number of groups (must be &gt; {@code group})
     * @param frame  Frames since the jump, 0 on the frame of the jump
     * @return The colour between the two, as far as the group has unfolded
     */
    int colour(final int field, final int target, final int group, final int groups, final long frame) {
        return mix(field, target, progress(group, groups, frame));
    }

    /**
     * Mixes two colours channel by channel.
     *
     * @param from The colour at 0 (0xRRGGBB)
     * @param to   The colour at 1 (0xRRGGBB)
     * @param t    The share of {@code to}, in [0, 1]
     * @return The mixed colour
     */
    static int mix(final int from, final int to, final double t) {
        int result = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            final int a = (from >> shift) & 0xFF;
            final int b = (to >> shift) & 0xFF;
            result |= ((int) Math.round(a + (b - a) * t)) << shift;
        }
        return result;
    }
}
