package org.evochora.cli.rendering.frame.descent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PrePassProgress}: the progress line of the pre-pass, driven by a clock the test
 * sets.
 */
@Tag("unit")
class PrePassProgressTest {

    private static final long SECOND = 1_000_000_000L;

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
    private final AtomicLong clock = new AtomicLong();

    private String printed() {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void rewritesOneLineWithTheShareOfTheRangeElapsedAndEta() {
        final PrePassProgress progress = new PrePassProgress(out, clock::get, 1000);

        progress.update(10, 0);
        assertThat(printed()).as("nothing within the first half second").isEmpty();

        clock.set(10 * SECOND);
        progress.update(2500, 250);
        assertThat(printed()).startsWith("\r[==========").contains("25%")
            .contains("2500 recorded ticks, tick 250/1000").contains("Elapsed: 0:10").contains("ETA: 0:30");

        clock.set(10 * SECOND + 1);
        progress.update(2600, 260);
        assertThat(printed()).as("not again within half a second").doesNotContain("260/1000");
    }

    @Test
    void withoutAKnownEndTheLineHasNoShare() {
        final PrePassProgress progress = new PrePassProgress(out, clock::get, Long.MAX_VALUE);

        clock.set(SECOND);
        progress.update(7, 70);

        assertThat(printed()).isEqualTo("\rAncestry: 7 recorded ticks, tick 70 | Elapsed: 0:01");
    }

    @Test
    void endsWithTheTotalsOnALineOfTheirOwn() {
        final PrePassProgress progress = new PrePassProgress(out, clock::get, 10);
        clock.set(SECOND);
        progress.update(1, 5);
        final DescentRecord record = new DescentRecord(new int[]{-1, 0, 1, 1}, new byte[4], 3,
            new long[]{0, 5, 10}, new int[]{0, 1, 1}, new int[]{1, 3, 3});

        progress.finish(record);

        assertThat(printed()).contains("\n" + "Ancestry read from the batch files: 3 organisms, 3 recorded ticks, "
            + "2 distinct roots, ");
    }
}
