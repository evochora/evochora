package org.evochora.cli.rendering.frame;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.evochora.cli.rendering.AbstractFrameRenderer;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.runtime.model.EnvironmentProperties;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Minimap-style frame renderer with cell aggregation and organism glow effects.
 * <p>
 * This renderer produces output that matches the web visualizer's minimap style:
 * <ul>
 *   <li>Cell aggregation via majority voting (multiple world cells → one pixel)</li>
 *   <li>Soft glow sprites for organism clusters</li>
 *   <li>Density-based glow sizing</li>
 * </ul>
 * <p>
 * Cell background rendering is delegated to {@link EnvironmentBackgroundLayer}.
 * <p>
 * <strong>CLI Usage:</strong>
 * <pre>
 *   evochora video minimap --scale 0.3 --overlay info -o minimap.mkv
 * </pre>
 * <p>
 * <strong>Thread Safety:</strong> Not thread-safe. Use one renderer per thread.
 */
@Command(name = "minimap", description = "Minimap-style aggregated rendering with organism glow effects",
         mixinStandardHelpOptions = true)
public class MinimapFrameRenderer extends AbstractFrameRenderer {

    @Option(names = "--scale",
            description = "Fraction of world size (0 < scale < 1, default: ${DEFAULT-VALUE})",
            defaultValue = "0.3")
    private double scale;

    @Option(names = "--cluster-grid",
            description = "Organism clustering grid size in world cells (default: ${DEFAULT-VALUE})",
            defaultValue = "1")
    private int clusterGrid;

    // Glow configuration (matching web minimap)
    // Sprite sizes for density levels (scaled by output resolution)
    private static final int[] BASE_GLOW_SIZES = {6, 10, 14, 18};
    private static final int[] DENSITY_THRESHOLDS = {3, 10, 30};
    private static final int BASE_CORE_SIZE = 3;  // Solid center size (matching frontend coreSize)
    private static final int BASE_OUTPUT_WIDTH = 400;  // Reference width for glow scaling

    /**
     * Organism palette — keep in sync with ExactFrameRenderer and AppController.ORGANISM_PALETTE.
     * Colors are assigned in insertion order: first genome hash seen gets green, second gets blue, etc.
     */
    private static final int[] ORGANISM_PALETTE = {
        0x32cd32,  // Green
        0x1e90ff,  // Blue
        0xdc143c,  // Red
        0xffd700,  // Gold
        0xffa500,  // Orange
        0x9370db,  // Purple
        0x00ffff   // Cyan
    };

    // Dimensions (initialized in init())
    private int worldWidth;
    private int worldHeight;
    private int outputWidth;
    private int outputHeight;

    // Frame buffer
    private BufferedImage frame;
    private int[] frameBuffer;

    // Glow sprites (scaled for output resolution), cached per color
    private final Map<Integer, int[][]> glowSpriteCache = new HashMap<>();
    private int[] glowSizes;
    private int coreSize;

    // Environment background layer (cell aggregation + rendering)
    private EnvironmentBackgroundLayer background;

    // Lazy organism access (only deserialize when rendering, not when applying state)
    private TickData lastSnapshot;
    private TickDelta lastDelta;

    // Genome hash → palette index (insertion order, persists across frames for color consistency)
    private final Map<Long, Integer> genomeHashColorMap = new LinkedHashMap<>();

    // Reusable density buffer for glow rendering (avoids allocation per frame)
    private int[] glowDensity;

    private boolean initialized = false;

    /**
     * Default constructor for PicoCLI instantiation.
     */
    public MinimapFrameRenderer() {
        // Options populated by PicoCLI
    }

    @Override
    public void init(EnvironmentProperties envProps) {
        if (scale <= 0 || scale >= 1) {
            throw new IllegalArgumentException("Minimap scale must be between 0 and 1 (exclusive), got: " + scale);
        }

        super.init(envProps);
        this.worldWidth = envProps.getWorldShape()[0];
        this.worldHeight = envProps.getWorldShape()[1];
        this.outputWidth = Math.max(1, (int) (worldWidth * scale));
        this.outputHeight = Math.max(1, (int) (worldHeight * scale));

        // Frame buffer
        this.frame = new BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_RGB);
        this.frameBuffer = ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();

        // Scale glow sizes based on output resolution
        double glowScale = (double) outputWidth / BASE_OUTPUT_WIDTH;
        this.glowSizes = new int[BASE_GLOW_SIZES.length];
        for (int i = 0; i < BASE_GLOW_SIZES.length; i++) {
            this.glowSizes[i] = Math.max(2, (int) (BASE_GLOW_SIZES[i] * glowScale));
        }
        this.coreSize = Math.max(1, (int) (BASE_CORE_SIZE * glowScale));
        this.glowSpriteCache.clear();

        // Environment background
        this.background = new EnvironmentBackgroundLayer(worldWidth, worldHeight, outputWidth, outputHeight);

        int outputSize = outputWidth * outputHeight;
        this.glowDensity = new int[outputSize];

        this.lastSnapshot = null;
        this.lastDelta = null;
        this.initialized = true;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Core rendering API
    // ─────────────────────────────────────────────────────────────────────────────

    @Override
    protected int[] doRenderSnapshot(TickData snapshot) {
        applySnapshotState(snapshot);
        return renderCurrentState();
    }

    @Override
    protected int[] doRenderDelta(TickDelta delta) {
        applyDeltaState(delta);
        return renderCurrentState();
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

    @Override
    public int[] renderCurrentState() {
        ensureInitialized();
        background.renderTo(frameBuffer);
        renderOrganismGlows(getOrganismsLazy());
        return frameBuffer;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Organism glow rendering
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Returns organisms from the most recent tick data (lazy access).
     */
    private List<OrganismState> getOrganismsLazy() {
        if (lastDelta != null) {
            return lastDelta.getOrganismsList();
        } else if (lastSnapshot != null) {
            return lastSnapshot.getOrganismsList();
        }
        return List.of();
    }

    /**
     * Renders organism glow effects, colored by genome hash.
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

        // Render each genome hash group with its own color
        for (final var entry : groups.entrySet()) {
            final long genomeHash = entry.getKey();
            final List<OrganismState> group = entry.getValue();
            final int color = getGenomeHashColor(genomeHash);
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
     * Returns the palette color for a genome hash, using insertion-order assignment.
     * The first genome hash seen gets green, the second blue, etc.
     *
     * @param genomeHash The organism's genome hash.
     * @return RGB color from {@link #ORGANISM_PALETTE}.
     */
    private int getGenomeHashColor(long genomeHash) {
        if (genomeHash == 0) return 0x808080;
        return ORGANISM_PALETTE[genomeHashColorMap
                .computeIfAbsent(genomeHash, k -> genomeHashColorMap.size() % ORGANISM_PALETTE.length)];
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
    // Glow sprite rendering
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
     * Matches the frontend MinimapOrganismOverlay rendering style:
     * solid core + radial gradient (0.6 → 0.3 → 0 alpha).
     *
     * @param size  Total sprite size in pixels.
     * @param color RGB color (0xRRGGBB).
     * @return Pixel array with ARGB values.
     */
    private int[] createGlowSprite(int size, int color) {
        int[] pixels = new int[size * size];
        float center = size / 2.0f;
        float glowRadius = center;
        float coreRadius = coreSize / 2.0f;

        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = x - center + 0.5f;
                float dy = y - center + 0.5f;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);

                int alpha;
                if (dist <= coreRadius) {
                    // Solid core (fully opaque)
                    alpha = 255;
                } else if (dist <= glowRadius) {
                    // Radial gradient matching frontend: 0.6 → 0.3 → 0
                    float t = (dist - coreRadius) / (glowRadius - coreRadius);
                    float a;
                    if (t <= 0.5f) {
                        // 0.6 → 0.3
                        a = 0.6f - t * 0.6f;
                    } else {
                        // 0.3 → 0
                        a = 0.3f - (t - 0.5f) * 0.6f;
                    }
                    alpha = Math.max(0, (int) (a * 255));
                } else {
                    alpha = 0;
                }

                pixels[y * size + x] = (alpha << 24) | (r << 16) | (g << 8) | b;
            }
        }

        return pixels;
    }

    private int selectSpriteIndex(int count) {
        for (int i = 0; i < DENSITY_THRESHOLDS.length; i++) {
            if (count <= DENSITY_THRESHOLDS[i]) return i;
        }
        return glowSizes.length - 1;
    }

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
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────────

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("Renderer not initialized. Call init(EnvironmentProperties) first.");
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
