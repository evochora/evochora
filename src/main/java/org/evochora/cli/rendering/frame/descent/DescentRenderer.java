package org.evochora.cli.rendering.frame.descent;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.evochora.cli.rendering.AbstractFrameRenderer;
import org.evochora.cli.rendering.IVideoFrameRenderer;
import org.evochora.cli.rendering.frame.shared.EnvironmentBackgroundLayer;
import org.evochora.cli.rendering.frame.shared.GlowLayer;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.node.processes.http.api.visualizer.descent.Ancestry;
import org.evochora.runtime.model.EnvironmentProperties;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Descent renderer showing how the lines below a root spread through the environment, as soft
 * glows over the environment cell composition.
 * <p>
 * Every frame colours the living organisms by the line they descend from: the child of the
 * frame's root that is their ancestor. The root of a frame is the youngest common ancestor of the
 * organisms alive at its tick ({@code all}, the virtual root above the founders, when they have
 * none), or the root held with {@code --root}. The lines of a root are ranked by their
 * descendants over the organisms read and take the visualizer's palette in that order; a ninth
 * line and beyond take the tone of other lines, organisms not descended from a held root the
 * outside tone, the root itself white; the dead are not drawn. With {@code --shift-palette} every
 * root the video moves to turns the palette one pair of colours further than the root before, as
 * the visualizer does, so that a root jump shows as a change of colours: the first root takes the
 * palette as it is, the second starts at its third colour, the fifth at its first again. A held
 * root is a single root, so the option changes nothing there.
 * <p>
 * With {@code --shades} every change of the genome moves the colour of an organism a little
 * within its line, so that the video shows mutations happening all the time while every line
 * keeps its colour: the shade of an organism ({@link LineShade}) drifts along its genome chain
 * from its line's founder, and moves the lightness of the line's colour only, never the hue. A
 * line is still drawn in one pass: every pixel takes the mean shade of the organisms on it. The
 * root and the organisms outside a held root are not shaded, nor are the legend's squares. The
 * pre-pass works the shades out with or without the option, so the ancestry file is the same.
 * <p>
 * Renders in two layers:
 * <ol>
 *   <li><strong>Background:</strong> environment cell types via majority voting
 *       ({@link EnvironmentBackgroundLayer})</li>
 *   <li><strong>Foreground:</strong> organism glows by line ({@link GlowLayer}), the largest
 *       line on top</li>
 * </ol>
 * <p>
 * The root of a run only moves forward, to a descendant: only an extinction moves the common
 * ancestor of the living, and every such jump completes a sweep. At the jump the population is
 * one line of the old root, one colour, the field; after it the lines of the new root unfold from
 * that colour into their own over {@code --unfold-frames} frames ({@link LineUnfolding}).
 * <p>
 * Before the first frame the renderer reads the organism lists of the run up to the end of the
 * rendered range ({@link DescentHistory}), so that it knows the parent of every organism and the
 * root of every frame in advance; the database is never opened. The line sizes of a root count
 * the organisms read, so a range that ends before the run does ranks the lines by their
 * descendants up to its end. With {@code --ancestry} what the pre-pass finds is kept in a file
 * ({@link DescentRecord}) and read from it by later renders of the same run.
 * <p>
 * With {@code --legend} every frame carries a panel in its bottom-left corner that says what the
 * colours mean ({@link DescentLegend}): the root and its birth tick, the four largest coloured
 * lines with living organisms, each with its colour in the frame, its share of the living and its
 * birth tick, and the lines without a cell of their own, counted together. It lies level with
 * the panel of {@code --overlay info} in the bottom-right corner and has its height. A birth tick
 * is the first recorded tick by which the organism was read, exact to the recording interval
 * ({@link DescentHistory#birthTickOf}).
 * <p>
 * <strong>CLI Usage:</strong>
 * <pre>
 *   evochora video descent --scale 0.3 --overlay info --out descent.mkv
 *   evochora video descent --root 1234 --glow-size 1.5 --out descent.mkv
 *   evochora video descent --unfold-frames 120 --keep-field-colour --out descent.mkv
 *   evochora video descent --shift-palette --keep-field-colour --out descent.mkv
 *   evochora video descent --ancestry run.ancestry --threads 8 --out descent.mkv
 *   evochora video descent --legend --overlay info,logo --out descent.mkv
 *   evochora video descent --shades --legend --out descent.mkv
 * </pre>
 * <p>
 * <strong>Thread Safety:</strong> not thread-safe. Thread instances made by
 * {@link #createThreadInstance()} share the read-only history and the line ranking of every root;
 * frame buffers, glows, shade tables, legends and walk buffers are per instance, and there is no
 * shared mutable state.
 */
@Command(name = "descent", description = "Organism glows coloured by line of descent over environment background",
         mixinStandardHelpOptions = true)
public class DescentRenderer extends AbstractFrameRenderer {

    /**
     * The line palette in the order the lines take it, the largest line first. The same list as
     * {@code LINE_PALETTE} in the visualizer's {@code DescentColours.js}; the two are kept in step.
     */
    static final int[] PALETTE = {
        0x5cff3b,  // green
        0xff3bc8,  // magenta
        0x00c2ff,  // cyan
        0xff9f00,  // orange
        0x3b5cff,  // blue
        0xffe600,  // yellow
        0xb23bff,  // violet
        0x00ffc8   // teal
    };

    /** A line beyond the palette; {@code DESCENT_TONES.OTHER} in {@code DescentColours.js}. */
    static final int OTHER_TONE = 0x8f9bb3;

    /** An organism not descended from the root; {@code DESCENT_TONES.OUTSIDE} in {@code DescentColours.js}. */
    static final int OUTSIDE_TONE = 0x555555;

    /** The root itself while it is alive; {@code DESCENT_TONES.ROOT} in {@code DescentColours.js}. */
    static final int ROOT_TONE = 0xffffff;

    /** Number of palette pairs; {@code LINE_PALETTE_PAIRS} in {@code DescentColours.js}. */
    private static final int PALETTE_PAIRS = PALETTE.length / 2;

    /** Groups of lines: one per palette colour and one for the lines beyond it. */
    private static final int GROUPS = PALETTE.length + 1;
    /** Draw slot of the lines beyond the palette. */
    private static final int SLOT_OTHER = GROUPS - 1;
    /** Draw slot of the organisms outside the root. */
    private static final int SLOT_OUTSIDE = GROUPS;
    /** Draw slot of the root itself. */
    private static final int SLOT_ROOT = GROUPS + 1;
    /** Number of draw slots. */
    private static final int SLOTS = GROUPS + 2;

    @Option(names = "--scale",
            description = "Fraction of world size (0 < scale < 1, default: ${DEFAULT-VALUE})",
            defaultValue = "0.3")
    private double scale;

    @Option(names = "--glow-size",
            description = "Glow size multiplier (default: ${DEFAULT-VALUE})",
            defaultValue = "1.0")
    private double glowSize;

    @Option(names = "--root",
            description = "Hold one root for the whole video: an organism id, or 'all' for the "
                    + "founders. Without it, every frame takes the youngest common ancestor of "
                    + "the organisms alive at its tick.")
    private String root;

    @Option(names = "--unfold-frames",
            description = "Frames over which the lines of a new root unfold from the colour of "
                    + "the field they lie in (default: ${DEFAULT-VALUE})",
            defaultValue = "" + LineUnfolding.DEFAULT_FRAMES)
    private int unfoldFrames;

    @Option(names = "--ancestry",
            description = "Ancestry file of the run: read instead of the pre-pass over the batch "
                    + "files when it belongs to the run and reaches the end of the range; written "
                    + "by the pre-pass when it does not exist. Without it nothing is written.")
    private File ancestry;

    @Option(names = "--ancestry-overwrite",
            description = "Replace an ancestry file that belongs to another run, has another "
                    + "layout or ends before the range does, instead of stopping")
    private boolean ancestryOverwrite;

    @Option(names = "--keep-field-colour",
            description = "After a root jump, the largest line keeps the colour of the field "
                    + "instead of taking the first palette colour")
    private boolean keepFieldColour;

    @Option(names = "--shift-palette",
            description = "Turn the palette one pair of colours further at every root the video "
                    + "moves to, so that a root jump shows as a change of colours")
    private boolean shiftPalette;

    @Option(names = "--legend",
            description = "Draw a legend in the bottom-left corner: the root, the four largest "
                    + "lines alive with their colour, share of the living and birth tick, and the "
                    + "other lines alive counted together")
    private boolean legend;

    @Option(names = "--shades",
            description = "Move the colour of an organism a little lighter or darker within its "
                    + "line at every change of the genome along its descent, so that mutations "
                    + "show while every line keeps its hue")
    private boolean shades;

    private int outputWidth;
    private int outputHeight;
    private BufferedImage frame;
    private int[] frameBuffer;
    private EnvironmentBackgroundLayer background;
    private GlowLayer glow;
    private LineUnfolding unfolding;
    /** The held root, or {@code null} for a root that follows the living. */
    private Integer heldRoot;

    /** What the pre-pass found, shared read-only between thread instances. */
    private Prepared prepared;

    // Per-instance walk buffers, reused from frame to frame
    private final Int2IntOpenHashMap memo = Ancestry.newMemo();
    private final IntArrayList path = new IntArrayList();
    private final IntArrayList slots = new IntArrayList();
    private final int[] slotColours = new int[SLOTS];

    /** Colours by shade of every line group, {@code null} without {@code --shades}. */
    private int[][] shadeTables;
    /** The group colour every table of {@link #shadeTables} was worked out for, -1 before the first. */
    private int[] shadeTableColours;

    /** The legend, {@code null} without {@code --legend}. */
    private DescentLegend legendPanel;
    // Per-frame counts for the legend, reused from frame to frame
    private final int[] livingPerSlot = new int[SLOTS];
    private final IntOpenHashSet otherLines = new IntOpenHashSet();

    private TickData lastSnapshot;
    private TickDelta lastDelta;
    private boolean calledFromTemplate;
    private boolean initialized;

    /**
     * Default constructor for PicoCLI instantiation.
     */
    public DescentRenderer() {
        // Options populated by PicoCLI
    }

    /**
     * Checks the options and sets up the frame, the background and the glows.
     *
     * @param envProps Environment properties (world shape, topology).
     * @throws IllegalArgumentException if {@code --scale} is not in (0, 1), {@code --glow-size}
     *                                  is not positive, {@code --unfold-frames} is below 1,
     *                                  {@code --root} is neither {@code all} nor an organism id, or
     *                                  {@code --ancestry-overwrite} is given without
     *                                  {@code --ancestry}
     */
    @Override
    public void init(EnvironmentProperties envProps) {
        if (scale <= 0 || scale >= 1) {
            throw new IllegalArgumentException(
                    "Descent scale must be between 0 and 1 (exclusive), got: " + scale);
        }
        if (glowSize <= 0) {
            throw new IllegalArgumentException("--glow-size must be positive, got: " + glowSize);
        }
        if (ancestryOverwrite && ancestry == null) {
            throw new IllegalArgumentException("--ancestry-overwrite needs --ancestry");
        }
        this.unfolding = new LineUnfolding(unfoldFrames, keepFieldColour);
        this.heldRoot = parseRoot(root);

        super.init(envProps);
        int worldWidth = envProps.getWorldShape()[0];
        int worldHeight = envProps.getWorldShape()[1];
        // Round down to even dimensions (required by H.264/H.265 macroblock alignment)
        this.outputWidth = Math.max(2, (int) (worldWidth * scale) & ~1);
        this.outputHeight = Math.max(2, (int) (worldHeight * scale) & ~1);

        this.frame = new BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_RGB);
        this.frameBuffer = ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();
        this.background = new EnvironmentBackgroundLayer(worldWidth, worldHeight, outputWidth, outputHeight);
        this.glow = new GlowLayer(outputWidth, outputHeight, glowSize);
        if (shades) {
            this.shadeTables = new int[GROUPS][GlowLayer.SHADES];
            this.shadeTableColours = new int[GROUPS];
            Arrays.fill(shadeTableColours, -1);
        }

        this.lastSnapshot = null;
        this.lastDelta = null;
        this.initialized = true;
    }

    /**
     * Parses {@code --root}.
     *
     * @param value The option's value, {@code null} when it was not given
     * @return {@code null} for a root that follows the living, 0 for {@code all}, else the id
     * @throws IllegalArgumentException if the value is neither {@code all} nor a positive id
     */
    static Integer parseRoot(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        if ("all".equals(trimmed)) {
            return Ancestry.NO_PARENT;
        }
        try {
            final int id = Integer.parseInt(trimmed);
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException e) {
            // Reported below with the value as given
        }
        throw new IllegalArgumentException("Invalid --root value '" + value + "': expected an organism id or 'all'");
    }

    /**
     * Finds the parent of every organism and the root of every recorded tick up to the end of the
     * range, and works out the root, the ranked lines and the transitions of every frame.
     * <p>
     * Without {@code --ancestry} the pre-pass reads the organism lists of the run on
     * {@code --threads} threads. With it, a usable ancestry file is read instead; a missing one is
     * written by the pre-pass; an unusable one stops the command, or with
     * {@code --ancestry-overwrite} is replaced by the pre-pass.
     *
     * @param storage          The storage the frames are read from.
     * @param batchPaths       Every batch file of the run, in tick order.
     * @param runId            The run the batch files belong to.
     * @param startTick        First tick that is rendered as a frame, inclusive.
     * @param endTick          Last tick that is rendered as a frame, inclusive.
     * @param samplingInterval Only ticks that are a multiple of it are rendered.
     * @throws Exception                if reading the storage or the ancestry file, or writing the
     *                                  ancestry file fails
     * @throws IllegalArgumentException if the held root is not an organism of the run up to the
     *                                  end of the range, or the ancestry file exists and cannot be
     *                                  used while {@code --ancestry-overwrite} is not given
     * @throws IllegalStateException    if the recorded ticks are out of order or incomplete
     */
    @Override
    public void prepare(IBatchStorageRead storage, List<StoragePath> batchPaths, String runId,
                        long startTick, long endTick, int samplingInterval) throws Exception {
        ensureInitialized();
        final DescentRecord record = ancestry == null
            ? scan(storage, batchPaths, endTick)
            : readOrWriteAncestry(ancestry.toPath(), storage, batchPaths, runId, endTick);
        final DescentHistory history = DescentHistory.of(record, startTick, endTick);
        this.prepared = Prepared.of(history, heldRoot, samplingInterval, unfolding, shiftPalette);
        this.legendPanel = newLegend();
    }

    /**
     * Makes the legend for the frame size and the prepared range, or none without
     * {@code --legend}.
     */
    private DescentLegend newLegend() {
        return legend ? new DescentLegend(outputWidth, outputHeight, prepared.lastTick(), prepared.maxLines()) : null;
    }

    /**
     * Reads the ancestry file if it can be used, else runs the pre-pass and writes it.
     */
    private DescentRecord readOrWriteAncestry(final Path file, final IBatchStorageRead storage,
                                              final List<StoragePath> batchPaths, final String runId,
                                              final long endTick) throws Exception {
        final Path directory = file.toAbsolutePath().getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            throw new IllegalArgumentException("The directory of the ancestry file " + file + " does not exist");
        }
        if (Files.exists(file)) {
            final long start = System.nanoTime();
            try {
                final DescentRecord record = DescentRecord.read(file, runId, endTick);
                System.out.println(PrePassProgress.totals("Ancestry read from " + file, record,
                    (System.nanoTime() - start) / 1e9));
                return record;
            } catch (DescentRecord.UnusableFileException e) {
                if (!ancestryOverwrite) {
                    throw new IllegalArgumentException("The ancestry file " + file + " " + e.getMessage()
                        + "; give --ancestry-overwrite to replace it", e);
                }
                System.out.println("The ancestry file " + file + " " + e.getMessage() + "; replacing it");
            }
        }
        final DescentRecord record = scan(storage, batchPaths, endTick);
        record.write(file, runId);
        System.out.println("Ancestry written to " + file);
        return record;
    }

    /**
     * Runs the pre-pass with a progress line.
     */
    private DescentRecord scan(final IBatchStorageRead storage, final List<StoragePath> batchPaths,
                               final long endTick) throws Exception {
        final PrePassProgress progress = new PrePassProgress(System.out, System::nanoTime, endTick);
        final DescentRecord record = DescentHistory.scan(storage, batchPaths, endTick,
            videoOptions.threadCount, progress);
        progress.finish(record);
        return record;
    }

    /**
     * The palette turned by a number of pairs, as {@code lineColour(rank, pair)} in the
     * visualizer's {@code DescentColours.js}: rank {@code r} takes
     * {@code PALETTE[(2 * pair + r) % PALETTE.length]}.
     *
     * @param pair The pair the palette starts at; taken modulo the number of pairs
     * @return A new array with the palette's colours in the order the lines take them
     */
    static int[] paletteOfPair(final int pair) {
        final int start = Math.floorMod(pair, PALETTE_PAIRS) * 2;
        final int[] palette = new int[PALETTE.length];
        for (int rank = 0; rank < palette.length; rank++) {
            palette[rank] = PALETTE[(start + rank) % PALETTE.length];
        }
        return palette;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Core rendering API
    // ─────────────────────────────────────────────────────────────────────────────

    @Override
    protected int[] doRenderSnapshot(TickData snapshot) {
        applySnapshotState(snapshot);
        calledFromTemplate = true;
        try {
            return renderCurrentState();
        } finally {
            calledFromTemplate = false;
        }
    }

    @Override
    protected int[] doRenderDelta(TickDelta delta) {
        applyDeltaState(delta);
        calledFromTemplate = true;
        try {
            return renderCurrentState();
        } finally {
            calledFromTemplate = false;
        }
    }

    @Override
    public void applySnapshotState(TickData snapshot) {
        ensureInitialized();
        background.processSnapshotCells(snapshot.getCellColumns());
        this.lastSnapshot = snapshot;
        this.lastDelta = null;
    }

    @Override
    public void applyDeltaState(TickDelta delta) {
        ensureInitialized();
        background.processDeltaCells(delta.getChangedCells());
        this.lastDelta = delta;
    }

    /**
     * Draws the background, the organism glows and, with {@code --legend}, the legend of the state
     * applied last.
     *
     * @return The pixel buffer of the frame.
     * @throws IllegalStateException if no state was applied, {@link #prepare} has not run, or the
     *                               tick was not read by it
     */
    @Override
    public int[] renderCurrentState() {
        ensureInitialized();
        if (prepared == null) {
            throw new IllegalStateException("Descent renderer not prepared. Call prepare(...) before rendering.");
        }
        if (lastSnapshot == null) {
            throw new IllegalStateException("No tick applied before rendering");
        }
        final long tick = lastDelta != null ? lastDelta.getTickNumber() : lastSnapshot.getTickNumber();
        final List<OrganismState> organisms = lastDelta != null
            ? lastDelta.getOrganismsList() : lastSnapshot.getOrganismsList();

        final int index = prepared.history.indexOf(tick);
        final Segment segment = prepared.segmentAt(index);
        background.renderTo(frameBuffer);
        renderOrganismGlows(index, segment, organisms);
        if (legendPanel != null) {
            drawLegend(segment);
        }

        if (!calledFromTemplate) {
            if (lastDelta != null) {
                applyOverlays(lastDelta);
            } else {
                applyOverlays(lastSnapshot);
            }
        }
        return frameBuffer;
    }

    /**
     * Draws the living organisms of a tick, slot by slot: the outside, the other lines, the
     * coloured lines from the smallest to the largest, the root last; with {@code --shades} the
     * line groups by shade. With the legend, counts the living organisms of every slot and the
     * distinct lines beyond the palette on the way.
     */
    private void renderOrganismGlows(final int index, final Segment segment, final List<OrganismState> organisms) {
        final long frameInWindow = prepared.position(index) - prepared.position(segment.start);
        segment.colours(frameInWindow, unfolding, slotColours);

        final boolean counting = legendPanel != null;
        if (counting) {
            Arrays.fill(livingPerSlot, 0);
            otherLines.clear();
        }
        memo.clear();
        slots.clear();
        for (final OrganismState organism : organisms) {
            if (organism.getIsDead()) {
                slots.add(-1);
                continue;
            }
            final int id = organism.getOrganismId();
            final int line = lineOf(segment, id);
            final int slot = slotOfLine(segment, id, line);
            slots.add(slot);
            if (counting) {
                livingPerSlot[slot]++;
                if (slot == SLOT_OTHER) {
                    otherLines.add(line);
                }
            }
        }
        drawSlot(organisms, SLOT_OUTSIDE);
        for (int group = GROUPS - 1; group >= 0; group--) {
            drawSlot(organisms, group);
        }
        drawSlot(organisms, SLOT_ROOT);
    }

    private void drawSlot(final List<OrganismState> organisms, final int slot) {
        final boolean shaded = shadeTables != null && slot < GROUPS;
        boolean any = false;
        glow.clear();
        for (int i = 0; i < organisms.size(); i++) {
            if (slots.getInt(i) != slot) {
                continue;
            }
            any = true;
            final OrganismState organism = organisms.get(i);
            if (shaded) {
                final int shade = prepared.history.shadeOf(organism.getOrganismId());
                glow.add(background.worldCoordsToPixelIndex(
                    organism.getIp().getComponents(0), organism.getIp().getComponents(1)), shade);
                for (final Vector dp : organism.getDataPointersList()) {
                    glow.add(background.worldCoordsToPixelIndex(dp.getComponents(0), dp.getComponents(1)), shade);
                }
            } else {
                glow.add(background.worldCoordsToPixelIndex(
                    organism.getIp().getComponents(0), organism.getIp().getComponents(1)));
                for (final Vector dp : organism.getDataPointersList()) {
                    glow.add(background.worldCoordsToPixelIndex(dp.getComponents(0), dp.getComponents(1)));
                }
            }
        }
        if (!any) {
            return;
        }
        if (shaded) {
            glow.drawTo(frameBuffer, shadeTableOf(slot));
        } else {
            glow.drawTo(frameBuffer, slotColours[slot]);
        }
    }

    /**
     * The colours by shade of a line group in its colour of the frame, worked out again only when
     * that colour changed since the table was worked out last.
     */
    private int[] shadeTableOf(final int group) {
        final int[] table = shadeTables[group];
        final int colour = slotColours[group];
        if (shadeTableColours[group] != colour) {
            for (int shade = -LineShade.MAX_SHADE; shade <= LineShade.MAX_SHADE; shade++) {
                table[shade + LineShade.MAX_SHADE] = LineShade.colour(colour, shade);
            }
            shadeTableColours[group] = colour;
        }
        return table;
    }

    /**
     * Draws the legend with the counts of the frame's slots.
     */
    private void drawLegend(final Segment segment) {
        int total = 0;
        for (final int count : livingPerSlot) {
            total += count;
        }
        legendPanel.update(segment.root, segment.lines.rootBirthTick, segment.lines.birthTicks, livingPerSlot,
            otherLines.size(), total, slotColours);
        legendPanel.draw(frame);
    }

    /**
     * The draw slot of a living organism under a segment's root.
     */
    private int slotOf(final Segment segment, final int id) {
        return slotOfLine(segment, id, lineOf(segment, id));
    }

    /**
     * The line of a living organism under a segment's root, as {@link Ancestry#lineOf} gives it.
     */
    private int lineOf(final Segment segment, final int id) {
        return prepared.history.ancestry().lineOf(segment.root, id, memo, path);
    }

    /**
     * The draw slot of a living organism under a segment's root, given its line.
     */
    private int slotOfLine(final Segment segment, final int id, final int line) {
        if (id == segment.root) {
            return SLOT_ROOT;
        }
        if (line == Ancestry.OUTSIDE) {
            return SLOT_OUTSIDE;
        }
        final int rank = segment.lines.rankOf(line);
        if (line == Ancestry.UNKNOWN || rank < 0) {
            throw new IllegalStateException("Organism " + id + " has no line below root " + segment.root
                + " although the pre-pass read it");
        }
        return Math.min(rank, GROUPS - 1);
    }

    /**
     * The colour a living organism is drawn in at a recorded tick; with {@code --shades} an
     * organism of a line group in its own shade (a pixel shared with other organisms takes the
     * mean shade of them all).
     *
     * @param tick A recorded tick of the prepared range
     * @param id   The id of an organism alive at the tick
     * @return The colour (0xRRGGBB)
     * @throws IllegalStateException if {@link #prepare} has not run or the tick was not read by it
     */
    int colourOf(final long tick, final int id) {
        final int index = prepared.history.indexOf(tick);
        final Segment segment = prepared.segmentAt(index);
        final int[] colours = new int[SLOTS];
        segment.colours(prepared.position(index) - prepared.position(segment.start), unfolding, colours);
        memo.clear();
        final int slot = slotOf(segment, id);
        if (shadeTables != null && slot < GROUPS) {
            return LineShade.colour(colours[slot], prepared.history.shadeOf(id));
        }
        return colours[slot];
    }

    /**
     * The root of a recorded tick.
     *
     * @param tick A recorded tick of the prepared range
     * @return The root the tick's frame is coloured by, 0 for {@code all}
     * @throws IllegalStateException if {@link #prepare} has not run or the tick was not read by it
     */
    int rootOf(final long tick) {
        return prepared.segmentAt(prepared.history.indexOf(tick)).root;
    }

    /**
     * The legend this instance draws.
     *
     * @return The legend, {@code null} without {@code --legend} or before {@link #prepare}
     */
    DescentLegend legend() {
        return legendPanel;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Thread instance sharing
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Creates a thread-local renderer instance that shares what the pre-pass found.
     * <p>
     * The shared state is read-only, so every instance colours every frame the same way. Frame
     * buffers, glows and walk buffers remain per instance.
     *
     * @return A new renderer instance sharing this renderer's prepared state.
     */
    @Override
    public IVideoFrameRenderer createThreadInstance() {
        DescentRenderer copy = (DescentRenderer) super.createThreadInstance();
        copy.prepared = this.prepared;
        copy.legendPanel = copy.newLegend();
        return copy;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Prepared state (read-only, shared across thread instances)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * The lines of one root, ranked by size, with the birth ticks of the root and of its coloured
     * lines.
     */
    private static final class RootLines {
        private final Int2IntOpenHashMap rankOfLine = new Int2IntOpenHashMap();
        private final int groups;
        private final int lineCount;
        /** Birth tick of the root; not set for the virtual root. */
        private final long rootBirthTick;
        /** Birth tick of the line of every coloured rank the root has. */
        private final long[] birthTicks;

        private RootLines(final DescentHistory history, final int root) {
            final int[] ids = new Ancestry.LineCount(root).extend(history.ancestry()).sizes().ids();
            rankOfLine.defaultReturnValue(-1);
            for (int rank = 0; rank < ids.length; rank++) {
                rankOfLine.put(ids[rank], rank);
            }
            this.groups = Math.min(ids.length, GROUPS);
            this.lineCount = ids.length;
            this.rootBirthTick = root == Ancestry.NO_PARENT ? Long.MIN_VALUE : history.birthTickOf(root);
            this.birthTicks = new long[Math.min(ids.length, PALETTE.length)];
            for (int rank = 0; rank < birthTicks.length; rank++) {
                birthTicks[rank] = history.birthTickOf(ids[rank]);
            }
        }

        int rankOf(final int line) {
            return rankOfLine.get(line);
        }
    }

    /**
     * A stretch of recorded ticks with one root, and the colours its lines unfold into: the
     * segment's palette in rank order, as {@link LineUnfolding#targets} hands them out.
     */
    private static final class Segment {
        private final int start;
        private final int root;
        private final RootLines lines;
        private final int field;
        private final int[] targets;

        private Segment(final int start, final int root, final RootLines lines, final int field,
                        final LineUnfolding unfolding, final int[] palette) {
            this.start = start;
            this.root = root;
            this.lines = lines;
            this.field = field;
            this.targets = unfolding.targets(palette, OTHER_TONE, field, lines.groups);
        }

        /**
         * The colour of every slot at a frame of the segment.
         */
        void colours(final long frameInWindow, final LineUnfolding unfolding, final int[] out) {
            for (int group = 0; group < GROUPS; group++) {
                out[group] = group < targets.length ? colourOf(group, frameInWindow, unfolding) : OTHER_TONE;
            }
            out[SLOT_OUTSIDE] = OUTSIDE_TONE;
            out[SLOT_ROOT] = ROOT_TONE;
        }

        int colourOf(final int group, final long frameInWindow, final LineUnfolding unfolding) {
            if (field == LineUnfolding.NO_FIELD) {
                return targets[group];
            }
            return unfolding.colour(field, targets[group], group, lines.groups, frameInWindow);
        }
    }

    /**
     * The history of the pre-pass and the segments of the range, read-only once made.
     */
    private static final class Prepared {
        private final DescentHistory history;
        private final int samplingInterval;
        private final int[] segmentStarts;
        private final List<Segment> segments;

        private Prepared(final DescentHistory history, final int samplingInterval, final List<Segment> segments) {
            this.history = history;
            this.samplingInterval = samplingInterval;
            this.segments = segments;
            this.segmentStarts = new int[segments.size()];
            for (int i = 0; i < segmentStarts.length; i++) {
                segmentStarts[i] = segments.get(i).start;
            }
        }

        /**
         * Cuts the range into segments of one root. A root held for the whole video is one
         * segment; otherwise every change of the root opens a segment whose field is the colour
         * the new root's line had on the last frame of the segment before. With
         * {@code shiftPalette} the segment with index {@code n} takes the palette turned by
         * {@code n} pairs ({@link #paletteOfPair}), else every segment takes the palette as it is.
         */
        static Prepared of(final DescentHistory history, final Integer heldRoot, final int samplingInterval,
                           final LineUnfolding unfolding, final boolean shiftPalette) {
            final Ancestry ancestry = history.ancestry();
            final Map<Integer, RootLines> lines = new HashMap<>();
            final List<Segment> segments = new ArrayList<>();
            if (heldRoot != null) {
                if (heldRoot != Ancestry.NO_PARENT && ancestry.parentOf(heldRoot) == Ancestry.UNREAD) {
                    throw new IllegalArgumentException("--root " + heldRoot
                        + " is not an organism of the run up to the end of the rendered range");
                }
                segments.add(new Segment(0, heldRoot, new RootLines(history, heldRoot), LineUnfolding.NO_FIELD,
                    unfolding, PALETTE));
                return new Prepared(history, samplingInterval, segments);
            }
            final Int2IntOpenHashMap memo = Ancestry.newMemo();
            final IntArrayList path = new IntArrayList();
            for (int index = 0; index < history.tickCount(); index++) {
                final int root = history.rootAt(index);
                if (index > 0 && root == history.rootAt(index - 1)) {
                    continue;
                }
                final RootLines rootLines = lines.computeIfAbsent(root, r -> new RootLines(history, r));
                int field = LineUnfolding.NO_FIELD;
                if (index > 0) {
                    final Segment before = segments.get(segments.size() - 1);
                    memo.clear();
                    final int line = ancestry.lineOf(before.root, root, memo, path);
                    final int rank = before.lines.rankOf(line);
                    if (line <= 0 || rank < 0) {
                        throw new IllegalStateException("Root " + root + " at tick " + history.tickAt(index)
                            + " does not descend from the root " + before.root + " of the tick before");
                    }
                    field = before.colourOf(Math.min(rank, GROUPS - 1),
                        position(history, samplingInterval, index - 1)
                            - position(history, samplingInterval, before.start), unfolding);
                }
                final int[] palette = shiftPalette ? paletteOfPair(segments.size()) : PALETTE;
                segments.add(new Segment(index, root, rootLines, field, unfolding, palette));
            }
            return new Prepared(history, samplingInterval, segments);
        }

        /**
         * The last recorded tick of the range, 0 when the range holds none.
         */
        long lastTick() {
            return history.tickCount() == 0 ? 0 : history.tickAt(history.tickCount() - 1);
        }

        /**
         * The most lines any root of the range has.
         */
        int maxLines() {
            int most = 0;
            for (final Segment segment : segments) {
                most = Math.max(most, segment.lines.lineCount);
            }
            return most;
        }

        /**
         * The segment a recorded tick lies in.
         */
        Segment segmentAt(final int index) {
            int found = Arrays.binarySearch(segmentStarts, index);
            if (found < 0) {
                found = -found - 2;
            }
            return segments.get(found);
        }

        /**
         * The frame position of a recorded tick: its place among the recorded ticks when every
         * tick is rendered, else the number of the sample tick that shows it.
         */
        long position(final int index) {
            return position(history, samplingInterval, index);
        }

        private static long position(final DescentHistory history, final int samplingInterval, final int index) {
            if (samplingInterval == 1) {
                return index;
            }
            final long tick = history.tickAt(index);
            return Math.floorDiv(tick + samplingInterval - 1, samplingInterval);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────────

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException(
                    "Renderer not initialized. Call init(EnvironmentProperties) first.");
        }
    }

    @Override
    public BufferedImage getFrame() {
        ensureInitialized();
        return frame;
    }

    @Override
    public int getImageWidth() {
        ensureInitialized();
        return outputWidth;
    }

    @Override
    public int getImageHeight() {
        ensureInitialized();
        return outputHeight;
    }
}
