package org.evochora.cli.rendering.frame;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.evochora.cli.rendering.AbstractFrameRenderer;
import org.evochora.cli.rendering.IVideoFrameRenderer;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.runtime.model.EnvironmentProperties;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Lineage renderer showing organism territories as soft glows colored by genome lineage,
 * overlaid on the environment cell composition.
 * <p>
 * Renders in two layers:
 * <ol>
 *   <li><strong>Background:</strong> Environment cell types via majority voting
 *       (delegated to {@link EnvironmentBackgroundLayer})</li>
 *   <li><strong>Foreground:</strong> Organism glows colored by genome hash, with
 *       lineage-aware HSL hue assignment so related genomes appear in similar colors</li>
 * </ol>
 * <p>
 * Color assignment: each genome hash receives an HSL hue. When a child genome descends
 * from a parent, its hue is derived from the parent's hue with a small shift, creating
 * visual continuity for related lineages. Unrelated lineages receive well-separated
 * colors via the golden ratio sequence.
 * <p>
 * With one or more {@code --clade <genomeHash>:<hue>} options the renderer switches to
 * clade coloring: a genome belongs to a clade if it is the given genome itself or if it
 * descends from it in the genome parent graph, which the renderer accumulates from the
 * organism parent chain while it processes ticks. Members are drawn in hues around the
 * clade's base hue with a deterministic per-genome deviation in hue (at most the
 * {@code --clade-spread} half-width, default 20°), saturation and lightness — genomes
 * within a clade stay visibly distinct, the clades stay clearly separated; every other
 * organism is gray. A genome stays gray until its descent from a root is proven — the proof can
 * arrive on a later tick, at which point the genome gains its clade color. Lineages that
 * never become resolvable are under-counted (gray), never miscolored.
 * <p>
 * The glow effect is softer than the minimap renderer: a semi-transparent core with
 * smooth quadratic falloff, producing gentle "territory clouds" rather than sharp dots.
 * <p>
 * <strong>CLI Usage:</strong>
 * <pre>
 *   evochora video lineage --scale 0.3 --overlay info -o lineage.mkv
 *   evochora video lineage --scale 0.5 --hue-shift 5 --glow-size 1.5 -o lineage.mkv
 *   evochora video lineage --clade -5127331321190430415:21 --clade -3579820754707553964:210 -o clades.mkv
 * </pre>
 * <p>
 * <strong>Thread Safety:</strong> Color state is shared between thread instances via
 * {@link #createThreadInstance()} and synchronized internally. Frame buffers and glow
 * sprites are per-instance (one renderer per thread).
 */
@Command(name = "lineage", description = "Lineage-colored organism glow rendering over environment background",
         mixinStandardHelpOptions = true)
public class LineageRenderer extends AbstractFrameRenderer {

    @Option(names = "--scale",
            description = "Fraction of world size (0 < scale < 1, default: ${DEFAULT-VALUE})",
            defaultValue = "0.3")
    private double scale;

    @Option(names = "--hue-shift",
            description = "Hue shift per mutation generation in degrees (default: ${DEFAULT-VALUE})",
            defaultValue = "25")
    private float hueShift;

    @Option(names = "--glow-size",
            description = "Glow size multiplier (default: ${DEFAULT-VALUE})",
            defaultValue = "1.0")
    private double glowSize;

    @Option(names = "--cluster-grid",
            description = "Organism clustering grid size in world cells (default: ${DEFAULT-VALUE})",
            defaultValue = "1")
    private int clusterGrid;

    @Option(names = "--clade",
            description = "Clade coloring: <genomeHash>:<hue> (repeatable). Genomes descending from "
                    + "<genomeHash> are drawn in hues around <hue> (degrees, 0-360); all other "
                    + "organisms are gray. Without this option, hues follow parent lineage.")
    private java.util.List<String> clades;

    @Option(names = "--clade-spread",
            description = "Half-width in degrees of the hue range each clade's member colors may "
                    + "use (default: ${DEFAULT-VALUE}). Choose smaller values when coloring many "
                    + "clades so their hue ranges stay separable.",
            defaultValue = "20")
    private float cladeSpread;

    // ─────────────────────────────────────────────────────────────────────────────
    // Glow configuration (softer than minimap)
    // ─────────────────────────────────────────────────────────────────────────────

    /** Sprite sizes for density levels (scaled by output resolution). */
    private static final int[] BASE_GLOW_SIZES = {8, 12, 16, 22};
    /** Density thresholds for glow size selection. */
    private static final int[] DENSITY_THRESHOLDS = {3, 10, 30};
    /** Reference width for glow scaling. */
    private static final int BASE_OUTPUT_WIDTH = 400;

    /** Peak opacity at glow center. */
    private static final int PEAK_ALPHA = 180;

    // ─────────────────────────────────────────────────────────────────────────────
    // Color constants
    // ─────────────────────────────────────────────────────────────────────────────

    /** Starting hue offset for the golden ratio sequence (degrees). */
    private static final float BASE_HUE_START = 120.0f;
    /** Golden angle for well-distributed hue assignment. */
    private static final float GOLDEN_ANGLE = 137.508f;
    /** Saturation for organism glow colors. */
    static final float GLOW_SATURATION = 0.75f;
    /** Lightness for organism glow colors. */
    static final float GLOW_LIGHTNESS = 0.60f;

    // ─────────────────────────────────────────────────────────────────────────────
    // Dimensions
    // ─────────────────────────────────────────────────────────────────────────────

    private int outputWidth;
    private int outputHeight;

    // ─────────────────────────────────────────────────────────────────────────────
    // Frame buffer
    // ─────────────────────────────────────────────────────────────────────────────

    private BufferedImage frame;
    private int[] frameBuffer;

    // ─────────────────────────────────────────────────────────────────────────────
    // Environment background layer
    // ─────────────────────────────────────────────────────────────────────────────

    private EnvironmentBackgroundLayer background;

    // ─────────────────────────────────────────────────────────────────────────────
    // Glow sprites (cached per color)
    // ─────────────────────────────────────────────────────────────────────────────

    private final Map<Integer, int[][]> glowSpriteCache = new HashMap<>();
    private int[] glowSizes;

    // ─────────────────────────────────────────────────────────────────────────────
    // Color state (shared across thread instances)
    // ─────────────────────────────────────────────────────────────────────────────

    /** Shared color state for lineage-aware hue assignment. Thread-safe. */
    private ColorState colorState;

    // ─────────────────────────────────────────────────────────────────────────────
    // Organism rendering state
    // ─────────────────────────────────────────────────────────────────────────────

    /** Reusable density buffer for glow rendering (avoids allocation per frame). */
    private int[] glowDensity;

    // ─────────────────────────────────────────────────────────────────────────────
    // Overlay support for sampling mode
    // ─────────────────────────────────────────────────────────────────────────────

    private TickData lastSnapshot;
    private TickDelta lastDelta;
    private boolean calledFromTemplate;

    private boolean initialized = false;

    /**
     * Default constructor for PicoCLI instantiation.
     */
    public LineageRenderer() {
        // Options populated by PicoCLI
    }

    @Override
    public void init(EnvironmentProperties envProps) {
        if (scale <= 0 || scale >= 1) {
            throw new IllegalArgumentException(
                    "Lineage scale must be between 0 and 1 (exclusive), got: " + scale);
        }

        super.init(envProps);
        int worldWidth = envProps.getWorldShape()[0];
        int worldHeight = envProps.getWorldShape()[1];
        // Round down to even dimensions (required by H.264/H.265 macroblock alignment)
        this.outputWidth = Math.max(2, (int) (worldWidth * scale) & ~1);
        this.outputHeight = Math.max(2, (int) (worldHeight * scale) & ~1);

        // Frame buffer
        this.frame = new BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_RGB);
        this.frameBuffer = ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();

        // Scale glow sizes based on output resolution and user multiplier
        double glowScale = (double) outputWidth / BASE_OUTPUT_WIDTH * glowSize;
        this.glowSizes = new int[BASE_GLOW_SIZES.length];
        for (int i = 0; i < BASE_GLOW_SIZES.length; i++) {
            this.glowSizes[i] = Math.max(2, (int) (BASE_GLOW_SIZES[i] * glowScale));
        }
        this.glowSpriteCache.clear();

        // Environment background
        this.background = new EnvironmentBackgroundLayer(worldWidth, worldHeight, outputWidth, outputHeight);

        // Glow density buffer
        int outputSize = outputWidth * outputHeight;
        this.glowDensity = new int[outputSize];

        if (cladeSpread <= 0 || cladeSpread >= 180) {
            throw new IllegalArgumentException(
                    "--clade-spread must be in (0, 180), got: " + cladeSpread);
        }

        // Color state (fresh on init; overwritten by createThreadInstance for shared state)
        this.colorState = new ColorState(hueShift, parseClades(), cladeSpread);

        this.lastSnapshot = null;
        this.lastDelta = null;
        this.initialized = true;
    }

    /**
     * Parses the {@code --clade} options into a genome-hash-to-hue map.
     *
     * @return Clade root genome hash to base hue; empty when no clades were given.
     * @throws IllegalArgumentException If an option is not {@code <genomeHash>:<hue>}
     *                                  or the hue is outside {@code [0, 360)}.
     */
    private Map<Long, Float> parseClades() {
        Map<Long, Float> roots = new LinkedHashMap<>();
        if (clades == null) {
            return roots;
        }
        for (String spec : clades) {
            int sep = spec.lastIndexOf(':');
            if (sep <= 0 || sep == spec.length() - 1) {
                throw new IllegalArgumentException(
                        "Invalid --clade value '" + spec + "': expected <genomeHash>:<hue>");
            }
            final long hash;
            final float hue;
            try {
                hash = Long.parseLong(spec.substring(0, sep));
                hue = Float.parseFloat(spec.substring(sep + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Invalid --clade value '" + spec + "': expected <genomeHash>:<hue>", e);
            }
            if (hue < 0 || hue >= 360) {
                throw new IllegalArgumentException(
                        "Invalid --clade hue " + hue + ": must be in [0, 360)");
            }
            roots.put(hash, hue);
        }
        return roots;
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
        colorState.processOrganisms(snapshot.getOrganismsList());
        background.processSnapshotCells(snapshot.getCellColumns());
        this.lastSnapshot = snapshot;
        this.lastDelta = null;
    }

    @Override
    public void applyDeltaState(TickDelta delta) {
        ensureInitialized();
        colorState.processOrganisms(delta.getOrganismsList());
        background.processDeltaCells(delta.getChangedCells());
        this.lastDelta = delta;
    }

    @Override
    public int[] renderCurrentState() {
        ensureInitialized();

        // Layer 1: Cell background
        background.renderTo(frameBuffer);

        // Layer 2: Organism glows colored by lineage
        renderOrganismGlows(getOrganismsLazy());

        // Layer 3: Overlays (only in sampling mode)
        if (!calledFromTemplate) {
            if (lastDelta != null) {
                applyOverlays(lastDelta);
            } else if (lastSnapshot != null) {
                applyOverlays(lastSnapshot);
            }
        }

        return frameBuffer;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Thread instance sharing
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Creates a thread-local renderer instance that shares color state with this renderer.
     * <p>
     * The shared {@link ColorState} ensures all thread instances assign the same hue
     * to the same genome hash. Frame buffers and glow sprites remain per-instance.
     *
     * @return A new renderer instance sharing this renderer's color state.
     */
    @Override
    public IVideoFrameRenderer createThreadInstance() {
        LineageRenderer copy = (LineageRenderer) super.createThreadInstance();
        copy.colorState = this.colorState;
        return copy;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Organism processing and color assignment
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Returns the glow color (packed RGB) for a genome hash.
     *
     * @param genomeHash The genome hash.
     * @return RGB color, or gray for unknown/zero genome hash.
     */
    private int getGenomeColor(long genomeHash) {
        if (genomeHash == 0) return 0x808080;
        Integer override = colorState.genomeColorOverride.get(genomeHash);
        if (override != null) return override;
        Float hue = colorState.genomeHueMap.get(genomeHash);
        if (hue == null) return 0x808080;
        return hslToRgb(hue, GLOW_SATURATION, GLOW_LIGHTNESS);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Organism glow rendering
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Returns organisms from the most recent tick data (lazy access).
     *
     * @return List of organisms.
     */
    private List<OrganismState> getOrganismsLazy() {
        if (lastDelta != null) return lastDelta.getOrganismsList();
        if (lastSnapshot != null) return lastSnapshot.getOrganismsList();
        return List.of();
    }

    /**
     * Renders organism glow effects, colored by genome hash lineage.
     * Organisms with the same genome hash share a color and are density-aggregated together.
     * Different genome hash groups are rendered as separate overlapping layers.
     *
     * @param organisms List of organisms to render.
     */
    private void renderOrganismGlows(List<OrganismState> organisms) {
        // Group living organisms by genome hash
        final Map<Long, List<OrganismState>> groups = new LinkedHashMap<>();
        for (final OrganismState org : organisms) {
            if (org.getIsDead()) continue;
            groups.computeIfAbsent(org.getGenomeHash(), k -> new ArrayList<>()).add(org);
        }

        final int totalPixels = glowDensity.length;

        // Render each genome hash group with its lineage color
        for (final var entry : groups.entrySet()) {
            final long genomeHash = entry.getKey();
            final List<OrganismState> group = entry.getValue();
            final int color = getGenomeColor(genomeHash);
            final int[][] sprites = getOrCreateGlowSprites(color);

            // Build density for this group
            java.util.Arrays.fill(glowDensity, 0);
            for (final OrganismState org : group) {
                addGlowDensity(org.getIp().getComponents(0), org.getIp().getComponents(1), totalPixels);
                for (final Vector dp : org.getDataPointersList()) {
                    addGlowDensity(dp.getComponents(0), dp.getComponents(1), totalPixels);
                }
            }

            // Render glows for this group
            for (int my = 0; my < outputHeight; my++) {
                for (int mx = 0; mx < outputWidth; mx++) {
                    final int count = glowDensity[my * outputWidth + mx];
                    if (count > 0) {
                        blitGlowSprite(mx, my, selectSpriteIndex(count), sprites);
                    }
                }
            }
        }
    }

    /**
     * Adds glow density for an organism position, applying coordinate quantization if enabled.
     *
     * @param wx World x coordinate.
     * @param wy World y coordinate.
     * @param totalPixels Total number of output pixels (for bounds checking).
     */
    private void addGlowDensity(int wx, int wy, int totalPixels) {
        if (clusterGrid > 1) {
            wx = (wx / clusterGrid) * clusterGrid;
            wy = (wy / clusterGrid) * clusterGrid;
        }
        int pixelIdx = background.worldCoordsToPixelIndex(wx, wy);
        if (pixelIdx >= 0 && pixelIdx < totalPixels) {
            glowDensity[pixelIdx]++;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Glow sprite rendering (softer than minimap)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Returns (or lazily creates) glow sprites for a given RGB color.
     *
     * @param color RGB color (0xRRGGBB).
     * @return Array of glow sprites for each density level.
     */
    private int[][] getOrCreateGlowSprites(int color) {
        return glowSpriteCache.computeIfAbsent(color, c -> {
            final int[][] sprites = new int[glowSizes.length][];
            for (int i = 0; i < glowSizes.length; i++) {
                sprites[i] = createGlowSprite(glowSizes[i], c);
            }
            return sprites;
        });
    }

    /**
     * Creates a single glow sprite with the given size and color.
     * Uses a smooth quartic radial falloff {@code (1 - r²)²} from center to edge,
     * producing a soft bell curve without hard core/edge boundaries.
     *
     * @param size  Total sprite size in pixels.
     * @param color RGB color (0xRRGGBB).
     * @return Pixel array with ARGB values.
     */
    private static int[] createGlowSprite(int size, int color) {
        int[] pixels = new int[size * size];
        float center = size / 2.0f;
        float radius = center;

        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = x - center + 0.5f;
                float dy = y - center + 0.5f;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);

                int alpha;
                if (dist >= radius) {
                    alpha = 0;
                } else {
                    // Smooth quartic bell: (1 - r²)² — no hard core/edge boundary
                    float t = dist / radius;
                    float falloff = 1.0f - t * t;
                    alpha = (int) (PEAK_ALPHA * falloff * falloff);
                }

                pixels[y * size + x] = (alpha << 24) | (r << 16) | (g << 8) | b;
            }
        }

        return pixels;
    }

    /**
     * Selects the glow sprite index based on organism density count.
     *
     * @param count Number of organisms at this pixel.
     * @return Sprite index (larger sprite for higher density).
     */
    private int selectSpriteIndex(int count) {
        for (int i = 0; i < DENSITY_THRESHOLDS.length; i++) {
            if (count <= DENSITY_THRESHOLDS[i]) return i;
        }
        return glowSizes.length - 1;
    }

    /**
     * Alpha-blends a glow sprite onto the frame buffer at the given center position.
     *
     * @param centerX    Center x in output pixels.
     * @param centerY    Center y in output pixels.
     * @param spriteIndex Index into the glow size/sprite arrays.
     * @param sprites    Sprite arrays for each density level.
     */
    private void blitGlowSprite(int centerX, int centerY, int spriteIndex, int[][] sprites) {
        int[] sprite = sprites[spriteIndex];
        int size = glowSizes[spriteIndex];
        int half = size / 2;
        int startX = centerX - half;
        int startY = centerY - half;

        for (int sy = 0; sy < size; sy++) {
            int fy = startY + sy;
            if (fy < 0 || fy >= outputHeight) continue;

            for (int sx = 0; sx < size; sx++) {
                int fx = startX + sx;
                if (fx < 0 || fx >= outputWidth) continue;

                int src = sprite[sy * size + sx];
                int alpha = (src >>> 24) & 0xFF;
                if (alpha == 0) continue;

                int idx = fy * outputWidth + fx;
                int dst = frameBuffer[idx];

                // Alpha blend
                int invA = 255 - alpha;
                int outR = (((src >> 16) & 0xFF) * alpha + ((dst >> 16) & 0xFF) * invA) / 255;
                int outG = (((src >> 8) & 0xFF) * alpha + ((dst >> 8) & 0xFF) * invA) / 255;
                int outB = ((src & 0xFF) * alpha + (dst & 0xFF) * invA) / 255;

                frameBuffer[idx] = (outR << 16) | (outG << 8) | outB;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // HSL → RGB conversion
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Converts HSL color values to a packed RGB integer.
     *
     * @param h Hue in degrees (0-360).
     * @param s Saturation (0-1).
     * @param l Lightness (0-1).
     * @return Packed RGB integer (0xRRGGBB).
     */
    static int hslToRgb(float h, float s, float l) {
        float c = (1.0f - Math.abs(2.0f * l - 1.0f)) * s;
        float hPrime = h / 60.0f;
        float x = c * (1.0f - Math.abs(hPrime % 2.0f - 1.0f));

        float r1, g1, b1;
        if (hPrime < 1) {
            r1 = c; g1 = x; b1 = 0;
        } else if (hPrime < 2) {
            r1 = x; g1 = c; b1 = 0;
        } else if (hPrime < 3) {
            r1 = 0; g1 = c; b1 = x;
        } else if (hPrime < 4) {
            r1 = 0; g1 = x; b1 = c;
        } else if (hPrime < 5) {
            r1 = x; g1 = 0; b1 = c;
        } else {
            r1 = c; g1 = 0; b1 = x;
        }

        float m = l - c / 2.0f;
        int r = Math.round((r1 + m) * 255.0f);
        int g = Math.round((g1 + m) * 255.0f);
        int b = Math.round((b1 + m) * 255.0f);

        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));

        return (r << 16) | (g << 8) | b;
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

    // ─────────────────────────────────────────────────────────────────────────────
    // Thread-safe shared color state
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Holds lineage color assignment state that is shared across thread instances.
     * <p>
     * The {@link #processOrganisms} method is synchronized to prevent race conditions
     * during color assignment (parent lookup + counter increment must be atomic).
     * The genome hue map uses {@link ConcurrentHashMap} for safe concurrent reads
     * during rendering.
     */
    static class ColorState {

        /** Genome hash → assigned hue (0-360 degrees). Persistent, never pruned. */
        final ConcurrentHashMap<Long, Float> genomeHueMap = new ConcurrentHashMap<>();
        /** Organism ID → genome hash (for parent lookup). Pruned to alive organisms. */
        final ConcurrentHashMap<Integer, Long> organismGenomeMap = new ConcurrentHashMap<>();
        /** Counter for golden-ratio hue assignment (unrelated lineages). */
        private final AtomicInteger baseHueCounter = new AtomicInteger(0);
        /** Hue shift per mutation generation in degrees. */
        private final float hueShift;
        /** Clade root genome hash → base hue. Empty means lineage-hue mode. */
        private final Map<Long, Float> cladeHues;
        /** Genome hash → parent genome hash, accumulated from organism parent links (clade mode only). */
        private final ConcurrentHashMap<Long, Long> genomeParent = new ConcurrentHashMap<>();
        /** Genome hash → clade root it was proven to descend from (clade mode only). */
        private final ConcurrentHashMap<Long, Long> genomeCladeRoot = new ConcurrentHashMap<>();
        /** Genome hash → final glow color for clade members (clade mode only). */
        final ConcurrentHashMap<Long, Integer> genomeColorOverride = new ConcurrentHashMap<>();
        /** Half-width of the per-genome hue deviation within a clade (degrees). */
        private final float cladeSpread;
        /** Saturation range of clade member colors. */
        private static final float CLADE_SATURATION_MIN = 0.60f, CLADE_SATURATION_MAX = 0.90f;
        /** Lightness range of clade member colors. */
        private static final float CLADE_LIGHTNESS_MIN = 0.52f, CLADE_LIGHTNESS_MAX = 0.68f;

        /**
         * Creates a new color state.
         *
         * @param hueShift    Hue shift per mutation generation in degrees (lineage-hue mode).
         * @param cladeHues   Clade root genome hash to base hue; an empty map selects the
         *                    lineage-hue mode, a non-empty map the clade coloring mode.
         * @param cladeSpread Half-width in degrees of the hue range a clade's member colors
         *                    may use (clade coloring mode).
         */
        ColorState(float hueShift, Map<Long, Float> cladeHues, float cladeSpread) {
            this.hueShift = hueShift;
            this.cladeHues = cladeHues;
            this.cladeSpread = cladeSpread;
        }

        private boolean cladeMode() {
            return !cladeHues.isEmpty();
        }

        /**
         * Processes the organism list in two passes to ensure parent genome hashes
         * are available before assigning child colors.
         * <p>
         * Synchronized to prevent race conditions when multiple thread instances
         * process different frames concurrently.
         *
         * @param organisms List of organisms from snapshot or delta.
         */
        synchronized void processOrganisms(List<OrganismState> organisms) {
            Set<Integer> aliveIds = new HashSet<>();

            // Pass 1: Record all organism → genome hash mappings
            for (OrganismState org : organisms) {
                organismGenomeMap.put(org.getOrganismId(), org.getGenomeHash());
                if (!org.getIsDead()) {
                    aliveIds.add(org.getOrganismId());
                }
            }

            // Pass 1b (clade mode): grow the genome parent graph from organism parent links
            if (cladeMode()) {
                for (OrganismState org : organisms) {
                    long genomeHash = org.getGenomeHash();
                    if (genomeHash == 0 || !org.hasParentId()) {
                        continue;
                    }
                    Long parentGenome = organismGenomeMap.get(org.getParentId());
                    if (parentGenome != null && parentGenome != 0 && parentGenome != genomeHash) {
                        genomeParent.putIfAbsent(genomeHash, parentGenome);
                    }
                }
            }

            // Pass 2: Assign colors (now parent lookups succeed regardless of list order)
            for (OrganismState org : organisms) {
                long genomeHash = org.getGenomeHash();
                if (genomeHash != 0 && !genomeHueMap.containsKey(genomeHash)) {
                    assignGenomeColor(org);
                }
            }

            // Prune dead organisms to prevent unbounded memory growth
            if (organismGenomeMap.size() > aliveIds.size() * 2) {
                organismGenomeMap.keySet().retainAll(aliveIds);
            }
        }

        /**
         * Assigns an HSL hue to a genome hash based on lineage.
         * <p>
         * In clade mode the hue encodes clade membership (see {@link #assignCladeColor});
         * otherwise, if the organism has a parent with a known genome hash color, the
         * child's hue is derived with a small shift, and unrelated lineages receive a
         * new base hue via the golden ratio sequence.
         *
         * @param org The organism whose genome hash needs a color.
         */
        private void assignGenomeColor(OrganismState org) {
            if (cladeMode()) {
                assignCladeColor(org);
                return;
            }
            long genomeHash = org.getGenomeHash();

            if (org.hasParentId()) {
                Long parentGenome = organismGenomeMap.get(org.getParentId());
                if (parentGenome != null && genomeHueMap.containsKey(parentGenome)) {
                    float parentHue = genomeHueMap.get(parentGenome);
                    // Alternate direction based on genome hash to spread siblings
                    float direction = (genomeHash % 2 == 0) ? 1.0f : -1.0f;
                    float childHue = (parentHue + direction * hueShift + 360.0f) % 360.0f;
                    genomeHueMap.put(genomeHash, childHue);
                    return;
                }
            }

            // No known parent lineage — assign from golden ratio sequence
            float hue = (BASE_HUE_START + baseHueCounter.getAndIncrement() * GOLDEN_ANGLE) % 360.0f;
            genomeHueMap.put(genomeHash, hue);
        }

        /**
         * Tries to prove clade membership for a genome and assigns its color on success.
         * <p>
         * Membership is proven by walking the accumulated genome parent graph towards a
         * clade root. Proven members receive the clade's base hue (the root itself) or the
         * base hue plus a deterministic per-genome deviation of at most
         * ±{@value #CLADE_JITTER_DEGREES}° (descendants), and keep that color. A genome
         * whose descent cannot be proven yet stays uncolored (gray) and is retried on
         * later ticks as the graph grows, so unresolvable lineages under-count clade
         * membership rather than miscoloring it.
         *
         * @param org The organism whose genome hash needs a membership decision.
         */
        private void assignCladeColor(OrganismState org) {
            long genomeHash = org.getGenomeHash();
            Long root = findCladeRoot(genomeHash);
            if (root == null) {
                return;
            }
            if (genomeHash == root) {
                genomeHueMap.put(genomeHash, cladeHues.get(root));
                return;
            }
            float hue = cladeMemberHue(root, genomeHash);
            genomeHueMap.put(genomeHash, hue);
            genomeColorOverride.put(genomeHash, cladeMemberColor(hue, genomeHash));
        }

        /**
         * Walks the genome parent graph from a genome towards a clade root, memoizing
         * every proven genome on the path.
         *
         * The walk stops on a cycle: reversion pairs can produce cyclic parent edges,
         * and a cyclic chain proves no descent.
         *
         * @param genomeHash The genome to resolve.
         * @return The clade root the genome descends from, or {@code null} if its descent
         *         is not (yet) provable from the accumulated graph.
         */
        private Long findCladeRoot(long genomeHash) {
            List<Long> path = new ArrayList<>();
            Set<Long> visited = new HashSet<>();
            Long current = genomeHash;
            Long root = null;
            while (current != null && visited.add(current)) {
                Long known = genomeCladeRoot.get(current);
                if (known != null) {
                    root = known;
                    break;
                }
                if (cladeHues.containsKey(current)) {
                    root = current;
                    path.add(current);
                    break;
                }
                path.add(current);
                current = genomeParent.get(current);
            }
            if (root != null) {
                for (Long g : path) {
                    genomeCladeRoot.put(g, root);
                }
            }
            return root;
        }

        /**
         * The hue of a clade member: the clade's base hue plus a deterministic per-genome
         * deviation of at most ± the configured clade spread.
         *
         * @param root       The clade root genome hash.
         * @param genomeHash The member genome hash.
         * @return Hue in degrees, in {@code [0, 360)}.
         */
        private float cladeMemberHue(long root, long genomeHash) {
            long mixed = genomeHash * 0x9E3779B97F4A7C15L;
            float unit = Long.remainderUnsigned(mixed >>> 16, 4001) / 4000.0f;
            float jitter = (2.0f * unit - 1.0f) * cladeSpread;
            return (cladeHues.get(root) + jitter + 360.0f) % 360.0f;
        }

        /**
         * The glow color of a clade member: its jittered hue combined with deterministic
         * per-genome saturation and lightness within a fixed band. Different genomes of one
         * clade are therefore visibly distinct while the clade's hue region stays
         * unmistakable; the root genome itself keeps the exact base color.
         *
         * @param hue        The member's hue from {@link #cladeMemberHue}.
         * @param genomeHash The member genome hash.
         * @return Packed RGB glow color.
         */
        private int cladeMemberColor(float hue, long genomeHash) {
            long mixed = genomeHash * 0x9E3779B97F4A7C15L;
            float saturation = CLADE_SATURATION_MIN + (CLADE_SATURATION_MAX - CLADE_SATURATION_MIN)
                    * (Long.remainderUnsigned(mixed >>> 24, 97) / 96.0f);
            float lightness = CLADE_LIGHTNESS_MIN + (CLADE_LIGHTNESS_MAX - CLADE_LIGHTNESS_MIN)
                    * (Long.remainderUnsigned(mixed >>> 40, 89) / 88.0f);
            return hslToRgb(hue, saturation, lightness);
        }
    }
}
