package org.evochora.cli.rendering.frame.descent;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.evochora.node.processes.http.api.visualizer.descent.Ancestry;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

/**
 * What the pre-pass of the {@code descent} renderer found over a run, from its first recorded
 * tick up to a last one, and the ancestry file that keeps it between invocations.
 * <p>
 * Key features:
 * <ul>
 *   <li><em>Parents</em>: organism id to parent id for every organism read,
 *       {@link Ancestry#NO_PARENT} for a founder, {@link Ancestry#UNREAD} for an id that appears
 *       in no recorded tick.</li>
 *   <li><em>Shades</em>: organism id to its shade within its line ({@link LineShade}), in
 *       [{@code -}{@link LineShade#MAX_SHADE}, {@link LineShade#MAX_SHADE}], 0 for an id that
 *       appears in no recorded tick.</li>
 *   <li><em>Recorded ticks</em>, each with its root (the youngest common ancestor of the organisms
 *       alive at it, {@link Ancestry#NO_PARENT} when they have none) and its limit (the highest
 *       organism id read up to and including it), so that a range ending before the last tick
 *       sees only the organisms read by its end.</li>
 *   <li><em>Ancestry file</em>: {@link #write} and {@link #read}. The layout, big-endian:
 *       <pre>
 *   int   MAGIC
 *   int   LAYOUT_VERSION
 *   UTF   run id (DataOutput.writeUTF)
 *   long  last recorded tick covered (Long.MIN_VALUE when no tick was read)
 *   int   organisms: the highest organism id read
 *   int   recorded ticks
 *   int   parent of every id from 1 to organisms
 *   byte  shade of every id from 1 to organisms
 *   per recorded tick, in tick order: long tick, int root, int limit
 *       </pre>
 *       The file ends right after the last tick.</li>
 * </ul>
 * <p>
 * Heap: five bytes per organism and sixteen bytes per recorded tick.
 * <p>
 * <strong>Thread Safety:</strong> immutable; the arrays are not copied and must not be written
 * after construction.
 */
final class DescentRecord {

    /** First four bytes of an ancestry file: {@code EVAN}. */
    static final int MAGIC = 0x4556414E;

    /** Layout version of the ancestry file this build writes and reads. */
    static final int LAYOUT_VERSION = 2;

    private static final int BUFFER_SIZE = 1 << 16;

    private final int[] parents;
    private final byte[] shades;
    private final int maxId;
    private final long[] ticks;
    private final int[] roots;
    private final int[] limits;

    /**
     * Creates a record.
     *
     * @param parents Parent of every id up to {@code maxId}, indexed by id; not copied
     * @param shades  Shade of every id up to {@code maxId}, indexed by id; not copied
     * @param maxId   The highest organism id read (&gt;= 0, below the lengths of {@code parents} and
     *                {@code shades})
     * @param ticks   The recorded ticks, ascending; not copied
     * @param roots   The root of every recorded tick, parallel to {@code ticks}; not copied
     * @param limits  The highest organism id read up to every recorded tick, parallel to
     *                {@code ticks}; not copied
     * @throws IllegalArgumentException if the arrays do not fit together
     */
    DescentRecord(final int[] parents, final byte[] shades, final int maxId, final long[] ticks, final int[] roots,
                  final int[] limits) {
        if (maxId < 0 || maxId >= parents.length || maxId >= shades.length || roots.length != ticks.length
                || limits.length != ticks.length) {
            throw new IllegalArgumentException("Inconsistent descent record: maxId " + maxId + ", "
                + parents.length + " parents, " + shades.length + " shades, " + ticks.length + " ticks, "
                + roots.length + " roots, " + limits.length + " limits");
        }
        this.parents = parents;
        this.shades = shades;
        this.maxId = maxId;
        this.ticks = ticks;
        this.roots = roots;
        this.limits = limits;
    }

    /**
     * The parents of every organism read.
     *
     * @return The array, indexed by id; entries above {@link #maxId()} are not part of the record
     */
    int[] parents() {
        return parents;
    }

    /**
     * The shades of every organism read within its line ({@link LineShade}).
     *
     * @return The array, indexed by id; entries above {@link #maxId()} are not part of the record
     */
    byte[] shades() {
        return shades;
    }

    /**
     * The highest organism id read.
     *
     * @return The id, 0 when no organism was read
     */
    int maxId() {
        return maxId;
    }

    /**
     * The number of recorded ticks read.
     *
     * @return The count
     */
    int tickCount() {
        return ticks.length;
    }

    /**
     * A recorded tick.
     *
     * @param index Its position among the recorded ticks, from 0
     * @return The tick number
     */
    long tickAt(final int index) {
        return ticks[index];
    }

    /**
     * The root of a recorded tick.
     *
     * @param index Its position among the recorded ticks
     * @return The youngest common ancestor of the organisms alive at the tick,
     *         {@link Ancestry#NO_PARENT} when they have none
     */
    int rootAt(final int index) {
        return roots[index];
    }

    /**
     * The highest organism id read up to and including a recorded tick.
     *
     * @param index Its position among the recorded ticks
     * @return The id
     */
    int limitAt(final int index) {
        return limits[index];
    }

    /**
     * The last recorded tick read.
     *
     * @return The tick, {@link Long#MIN_VALUE} when none was read
     */
    long lastTick() {
        return ticks.length == 0 ? Long.MIN_VALUE : ticks[ticks.length - 1];
    }

    /**
     * The number of different roots over all recorded ticks.
     *
     * @return The count
     */
    int distinctRoots() {
        return new IntOpenHashSet(roots).size();
    }

    /**
     * Writes the record as an ancestry file. The content goes to a temporary file in the same
     * directory first, which then replaces the target in one rename, so that the target is
     * either absent, the old file or the complete new one; the temporary file is removed on a
     * failure.
     *
     * @param file  The ancestry file; its directory must exist
     * @param runId The run the record belongs to
     * @throws IOException if writing or renaming fails
     */
    void write(final Path file, final String runId) throws IOException {
        final Path target = file.toAbsolutePath();
        final Path temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temporary);
                 DataOutputStream data = new DataOutputStream(new BufferedOutputStream(out, BUFFER_SIZE))) {
                data.writeInt(MAGIC);
                data.writeInt(LAYOUT_VERSION);
                data.writeUTF(runId);
                data.writeLong(lastTick());
                data.writeInt(maxId);
                data.writeInt(ticks.length);
                for (int id = 1; id <= maxId; id++) {
                    data.writeInt(parents[id]);
                }
                data.write(shades, 1, maxId);
                for (int i = 0; i < ticks.length; i++) {
                    data.writeLong(ticks[i]);
                    data.writeInt(roots[i]);
                    data.writeInt(limits[i]);
                }
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Reads an ancestry file that has to belong to a run and reach a tick.
     *
     * @param file    The ancestry file
     * @param runId   The run it has to belong to
     * @param endTick The tick its last recorded tick has to reach
     * @return The record it holds
     * @throws UnusableFileException if the file is not an ancestry file, has another layout
     *                               version, belongs to another run, ends before {@code endTick},
     *                               or its content is cut short or inconsistent
     * @throws IOException           if reading the file fails
     */
    static DescentRecord read(final Path file, final String runId, final long endTick)
            throws UnusableFileException, IOException {
        try (InputStream in = Files.newInputStream(file);
             DataInputStream data = new DataInputStream(new BufferedInputStream(in, BUFFER_SIZE))) {
            final int magic = data.readInt();
            if (magic != MAGIC) {
                throw new UnusableFileException("is not an ancestry file");
            }
            final int version = data.readInt();
            if (version != LAYOUT_VERSION) {
                throw new UnusableFileException("has layout version " + version + ", this build reads version "
                    + LAYOUT_VERSION);
            }
            final String fileRunId = data.readUTF();
            if (!fileRunId.equals(runId)) {
                throw new UnusableFileException("belongs to run " + fileRunId + ", not to run " + runId);
            }
            final long lastTick = data.readLong();
            if (lastTick < endTick) {
                throw new UnusableFileException("covers the run up to tick " + lastTick
                    + " only, the rendered range ends at tick " + endTick);
            }
            return readBody(data, lastTick);
        } catch (EOFException e) {
            throw new UnusableFileException("ends before the content its header announces");
        }
    }

    private static DescentRecord readBody(final DataInputStream data, final long lastTick)
            throws UnusableFileException, IOException {
        final int maxId = data.readInt();
        final int count = data.readInt();
        if (maxId < 0 || count < 0) {
            throw new UnusableFileException("is damaged: " + maxId + " organisms, " + count + " recorded ticks");
        }
        final int[] parents = new int[maxId + 1];
        parents[0] = Ancestry.UNREAD;
        for (int id = 1; id <= maxId; id++) {
            final int parent = data.readInt();
            if (parent < Ancestry.UNREAD || parent >= id) {
                throw new UnusableFileException("is damaged: organism " + id + " has parent " + parent);
            }
            parents[id] = parent;
        }
        final byte[] shades = new byte[maxId + 1];
        data.readFully(shades, 1, maxId);
        for (int id = 1; id <= maxId; id++) {
            if (shades[id] < -LineShade.MAX_SHADE) {
                throw new UnusableFileException("is damaged: organism " + id + " has shade " + shades[id]);
            }
        }
        final long[] ticks = new long[count];
        final int[] roots = new int[count];
        final int[] limits = new int[count];
        for (int i = 0; i < count; i++) {
            ticks[i] = data.readLong();
            roots[i] = data.readInt();
            limits[i] = data.readInt();
            if (i > 0 && ticks[i] <= ticks[i - 1]) {
                throw new UnusableFileException("is damaged: recorded tick " + ticks[i] + " follows tick "
                    + ticks[i - 1]);
            }
            if (i > 0 && limits[i] < limits[i - 1]) {
                throw new UnusableFileException("is damaged: the organisms read shrink at recorded tick " + ticks[i]);
            }
            if (limits[i] < 0 || limits[i] > maxId || roots[i] < 0 || roots[i] > limits[i]) {
                throw new UnusableFileException("is damaged: recorded tick " + ticks[i] + " has root " + roots[i]
                    + " and limit " + limits[i]);
            }
        }
        if (data.read() >= 0) {
            throw new UnusableFileException("is damaged: it goes on after the content its header announces");
        }
        final DescentRecord record = new DescentRecord(parents, shades, maxId, ticks, roots, limits);
        if (record.lastTick() != lastTick) {
            throw new UnusableFileException("is damaged: its header names tick " + lastTick
                + " as the last, its content ends at tick " + record.lastTick());
        }
        return record;
    }

    /**
     * An ancestry file that cannot be used for a run and a range; the message completes the
     * sentence "The ancestry file ...".
     */
    static final class UnusableFileException extends Exception {
        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception.
         *
         * @param reason Why the file cannot be used, completing "The ancestry file ..."
         */
        UnusableFileException(final String reason) {
            super(reason);
        }
    }
}
