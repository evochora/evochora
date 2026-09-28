package org.evochora.cli.rendering.frame.descent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

/**
 * Tests for the ancestry file of the {@code descent} renderer: {@link DescentRecord#write} and
 * {@link DescentRecord#read}, and the renderer's {@code --ancestry} and
 * {@code --ancestry-overwrite}, on files in a temporary directory.
 * <p>
 * The run used throughout has two founder lines; the line of founder 2 dies out at tick 2, the
 * line of 4 at tick 4 (see {@link DescentHistoryTest}). Organisms 3, 5 and 6 change their
 * parent's genome, 4 keeps it; genomes are given at an organism's first appearance, where the
 * pre-pass reads them.
 */
@Tag("integration")
class DescentRecordTest {

    private static final String RUN = "run-a";

    @TempDir
    Path directory;

    private static DescentRunFixture run() {
        return new DescentRunFixture()
            .batch(0,
                "1:0@10,10#1 2:0@20,20#2",
                "1:0@10,10 2:0@20,20 3:1@30,30#3^1 4:1@40,40#1^1",
                "1:0@10,10 2:0@20,20+ 3:1@30,30 4:1@40,40")
            .batch(3,
                "1:0@10,10+ 3:1@30,30 4:1@40,40 5:3@50,50#5^3 6:3@60,60#6^3",
                "3:1@30,30 4:1@40,40+ 5:3@50,50 6:3@60,60");
    }

    private static DescentRecord scan(final DescentRunFixture run, final long endTick) throws Exception {
        return DescentHistory.scan(run.storage(), run.paths, endTick, 1, (n, t) -> { });
    }

    /** A renderer with the given options, prepared on the run up to its last tick. */
    private static DescentRenderer prepare(final DescentRunFixture run, final String runId, final String... args)
            throws Exception {
        final DescentRenderer renderer = new DescentRenderer();
        new CommandLine(renderer).parseArgs(args);
        renderer.init(new EnvironmentProperties(new int[]{100, 100}, false));
        renderer.prepare(run.storage(), run.paths, runId, 0, 4, 1);
        return renderer;
    }

    /** Writes a file with the given header and nothing after it. */
    private static void writeHeader(final Path file, final int magic, final int version, final String runId,
                                    final long lastTick) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); DataOutputStream data = new DataOutputStream(out)) {
            data.writeInt(magic);
            data.writeInt(version);
            data.writeUTF(runId);
            data.writeLong(lastTick);
        }
    }

    private long filesIn(final Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.count();
        }
    }

    @Test
    void aWrittenRecordReadsBackTheSame() throws Exception {
        final DescentRecord written = scan(run(), Long.MAX_VALUE);
        final Path file = directory.resolve("run.ancestry");

        written.write(file, RUN);
        final DescentRecord read = DescentRecord.read(file, RUN, 4);

        assertThat(read.maxId()).isEqualTo(6);
        assertThat(read.tickCount()).isEqualTo(5);
        assertThat(read.lastTick()).isEqualTo(4L);
        for (int id = 1; id <= 6; id++) {
            assertThat(read.parents()[id]).as("parent of %d", id).isEqualTo(written.parents()[id]);
            assertThat(read.shades()[id]).as("shade of %d", id).isEqualTo(written.shades()[id]);
        }
        assertThat(written.shades()[3]).isEqualTo(LineShade.drift((byte) 0, 3)).isNotZero();
        assertThat(written.shades()[6]).isEqualTo(LineShade.drift(written.shades()[3], 6));
        assertThat(written.shades()[4]).isZero();
        for (int i = 0; i < 5; i++) {
            assertThat(read.tickAt(i)).isEqualTo(written.tickAt(i));
            assertThat(read.rootAt(i)).isEqualTo(written.rootAt(i));
            assertThat(read.limitAt(i)).isEqualTo(written.limitAt(i));
        }
        assertThat(filesIn(directory)).as("no temporary file is left").isEqualTo(1);
    }

    @Test
    void anExistingFileIsReplacedInOneRenameWithoutATemporaryFileLeft() throws Exception {
        final Path file = directory.resolve("run.ancestry");
        scan(run(), 2).write(file, RUN);

        scan(run(), Long.MAX_VALUE).write(file, RUN);

        assertThat(DescentRecord.read(file, RUN, 4).lastTick()).isEqualTo(4L);
        assertThat(filesIn(directory)).isEqualTo(1);
    }

    @Test
    void aFileIsRejectedWithItsReason() throws Exception {
        final Path file = directory.resolve("run.ancestry");
        scan(run(), 2).write(file, RUN);

        assertThatThrownBy(() -> DescentRecord.read(file, "run-b", 2))
            .isInstanceOf(DescentRecord.UnusableFileException.class).hasMessageContaining("belongs to run run-a");
        assertThatThrownBy(() -> DescentRecord.read(file, RUN, 3))
            .isInstanceOf(DescentRecord.UnusableFileException.class).hasMessageContaining("up to tick 2 only");

        writeHeader(file, 0x12345678, DescentRecord.LAYOUT_VERSION, RUN, 4);
        assertThatThrownBy(() -> DescentRecord.read(file, RUN, 4))
            .isInstanceOf(DescentRecord.UnusableFileException.class).hasMessageContaining("not an ancestry file");

        writeHeader(file, DescentRecord.MAGIC, DescentRecord.LAYOUT_VERSION + 1, RUN, 4);
        assertThatThrownBy(() -> DescentRecord.read(file, RUN, 4))
            .isInstanceOf(DescentRecord.UnusableFileException.class).hasMessageContaining("layout version");

        // The layout before shades were kept: parents and ticks only
        writeHeader(file, DescentRecord.MAGIC, 1, RUN, 4);
        assertThatThrownBy(() -> DescentRecord.read(file, RUN, 4))
            .isInstanceOf(DescentRecord.UnusableFileException.class).hasMessageContaining("has layout version 1");

        writeHeader(file, DescentRecord.MAGIC, DescentRecord.LAYOUT_VERSION, RUN, 4);
        assertThatThrownBy(() -> DescentRecord.read(file, RUN, 4))
            .isInstanceOf(DescentRecord.UnusableFileException.class).hasMessageContaining("ends before");
    }

    @Test
    void aShadeOutsideTheRangeIsRejectedAsDamaged() throws Exception {
        final Path file = directory.resolve("run.ancestry");
        new DescentRecord(new int[]{-1, 0}, new byte[]{0, Byte.MIN_VALUE}, 1, new long[]{0}, new int[]{1},
            new int[]{1}).write(file, RUN);

        assertThatThrownBy(() -> DescentRecord.read(file, RUN, 0))
            .isInstanceOf(DescentRecord.UnusableFileException.class).hasMessageContaining("has shade -128");
    }

    @Test
    void theRendererWritesAMissingFileAndReadsItNextTimeInsteadOfTheBatchFiles() throws Exception {
        final Path file = directory.resolve("run.ancestry");
        final DescentRunFixture first = run();
        final DescentRenderer written = prepare(first, RUN, "--ancestry", file.toString());
        assertThat(first.read).hasSize(2);
        assertThat(DescentRecord.read(file, RUN, 4).tickCount()).isEqualTo(5);

        final DescentRunFixture second = run();
        final DescentRenderer read = prepare(second, RUN, "--ancestry", file.toString());

        assertThat(second.read).as("no batch file read").isEmpty();
        for (int tick = 0; tick <= 4; tick++) {
            assertThat(read.rootOf(tick)).isEqualTo(written.rootOf(tick));
        }
        assertThat(read.rootOf(4)).isEqualTo(3);
    }

    @Test
    void aFileOfAnotherRunStopsTheRendererUnlessItMayBeOverwritten() throws Exception {
        final Path file = directory.resolve("run.ancestry");
        scan(run(), Long.MAX_VALUE).write(file, "run-b");

        assertThatThrownBy(() -> prepare(run(), RUN, "--ancestry", file.toString()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("belongs to run run-b").hasMessageContaining("--ancestry-overwrite");

        final DescentRunFixture run = run();
        prepare(run, RUN, "--ancestry", file.toString(), "--ancestry-overwrite");
        assertThat(run.read).hasSize(2);
        assertThat(DescentRecord.read(file, RUN, 4).tickCount()).isEqualTo(5);
    }

    @Test
    void aFileEndingBeforeTheRangeStopsTheRendererUnlessItMayBeOverwritten() throws Exception {
        final Path file = directory.resolve("run.ancestry");
        scan(run(), 2).write(file, RUN);

        assertThatThrownBy(() -> prepare(run(), RUN, "--ancestry", file.toString()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("up to tick 2 only");

        prepare(run(), RUN, "--ancestry", file.toString(), "--ancestry-overwrite");
        assertThat(DescentRecord.read(file, RUN, 4).lastTick()).isEqualTo(4L);
    }

    @Test
    void aFileOfAnotherLayoutStopsTheRendererUnlessItMayBeOverwritten() throws Exception {
        final Path file = directory.resolve("run.ancestry");
        for (final int[] header : new int[][]{{0x12345678, DescentRecord.LAYOUT_VERSION},
                                               {DescentRecord.MAGIC, DescentRecord.LAYOUT_VERSION + 1}}) {
            writeHeader(file, header[0], header[1], RUN, 4);

            assertThatThrownBy(() -> prepare(run(), RUN, "--ancestry", file.toString()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--ancestry-overwrite");

            prepare(run(), RUN, "--ancestry", file.toString(), "--ancestry-overwrite");
            assertThat(DescentRecord.read(file, RUN, 4).tickCount()).isEqualTo(5);
        }
    }

    @Test
    void aFileInADirectoryThatDoesNotExistIsRejectedBeforeThePass() {
        final DescentRunFixture run = run();
        final Path file = directory.resolve("missing").resolve("run.ancestry");

        assertThatThrownBy(() -> prepare(run, RUN, "--ancestry", file.toString()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not exist");
        assertThat(run.read).isEmpty();
    }
}
