package org.evochora.cli.rendering.frame.descent;

import java.io.PrintStream;
import java.util.function.LongSupplier;

import org.evochora.cli.rendering.VideoRenderEngine;

/**
 * The progress line of the {@code descent} renderer's pre-pass, in the form of the video engine's
 * progress line for frames: one line rewritten in place at most twice a second, with the recorded
 * ticks read, the share of the tick range covered, the elapsed time and the estimated remaining
 * time, and a closing line with the totals.
 * <p>
 * The number of recorded ticks in the range is not known before they are read, so the share is
 * that of the tick numbers: from the first recorded tick to the end of the range.
 * <p>
 * <strong>Thread Safety:</strong> not thread-safe; called by the thread that runs the pass.
 */
final class PrePassProgress implements DescentHistory.Progress {

    private static final long UPDATE_INTERVAL_NANOS = 500_000_000L;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final int BAR_WIDTH = 40;

    private final PrintStream out;
    private final LongSupplier nanoClock;
    private final long endTick;
    private final long startNanos;
    private long lastUpdateNanos;
    private long firstTick = Long.MIN_VALUE;
    private boolean printed;

    /**
     * Creates a progress line that starts counting now.
     *
     * @param out       Where the line is printed
     * @param nanoClock The clock, in nanoseconds, as {@link System#nanoTime()}
     * @param endTick   The last tick the pass reads, {@link Long#MAX_VALUE} when it is not known
     */
    PrePassProgress(final PrintStream out, final LongSupplier nanoClock, final long endTick) {
        this.out = out;
        this.nanoClock = nanoClock;
        this.endTick = endTick;
        this.startNanos = nanoClock.getAsLong();
        this.lastUpdateNanos = startNanos;
    }

    /**
     * Rewrites the line, unless it was written less than half a second ago.
     *
     * @param recordedTicks Recorded ticks read so far
     * @param lastTick      The last recorded tick read
     */
    @Override
    public void update(final int recordedTicks, final long lastTick) {
        if (firstTick == Long.MIN_VALUE) {
            firstTick = lastTick;
        }
        final long now = nanoClock.getAsLong();
        if (now - lastUpdateNanos < UPDATE_INTERVAL_NANOS) {
            return;
        }
        lastUpdateNanos = now;
        final long elapsed = (now - startNanos) / NANOS_PER_MILLI;
        if (endTick != Long.MAX_VALUE && endTick > firstTick) {
            final double share = Math.min(1.0, Math.max(0.0, (lastTick - firstTick) / (double) (endTick - firstTick)));
            final long remaining = share > 0 ? (long) (elapsed * (1 - share) / share) : -1;
            final int filled = (int) (share * BAR_WIDTH);
            out.print(String.format("\r[%s%s] %d%% | Ancestry: %d recorded ticks, tick %d/%d | Elapsed: %s | ETA: %s",
                "=".repeat(filled), " ".repeat(BAR_WIDTH - filled), (int) (share * 100), recordedTicks, lastTick,
                endTick, VideoRenderEngine.formatTime(elapsed), VideoRenderEngine.formatTime(remaining)));
        } else {
            out.print(String.format("\rAncestry: %d recorded ticks, tick %d | Elapsed: %s",
                recordedTicks, lastTick, VideoRenderEngine.formatTime(elapsed)));
        }
        out.flush();
        printed = true;
    }

    /**
     * Ends the line and prints the totals of the pass.
     *
     * @param record What the pass found
     */
    void finish(final DescentRecord record) {
        if (printed) {
            out.println();
        }
        out.println(totals("Ancestry read from the batch files", record,
            (nanoClock.getAsLong() - startNanos) / (double) (NANOS_PER_MILLI * 1000)));
    }

    /**
     * The closing line of a pass or of an ancestry file read.
     *
     * @param source  Where the record came from
     * @param record  The record
     * @param seconds How long it took
     * @return The line, without a line break
     */
    static String totals(final String source, final DescentRecord record, final double seconds) {
        return String.format("%s: %d organisms, %d recorded ticks, %d distinct roots, %.1f s",
            source, record.maxId(), record.tickCount(), record.distinctRoots(), seconds);
    }
}
