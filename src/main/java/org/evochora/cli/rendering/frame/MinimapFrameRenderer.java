package org.evochora.cli.rendering.frame;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.evochora.cli.rendering.AbstractFrameRenderer;
import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.runtime.Config;
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
 * <strong>Performance:</strong> Uses generation numbers for O(1) state reset instead of
 * O(worldSize) Arrays.fill. Aggregation counts are built incrementally during cell processing.
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

    // Cell type constants (indices into CELL_COLORS)
    private static final int TYPE_CODE = 0;
    private static final int TYPE_DATA = 1;
    private static final int TYPE_ENERGY = 2;
    private static final int TYPE_STRUCTURE = 3;
    private static final int TYPE_LABEL = 4;
    private static final int TYPE_LABELREF = 5;
    private static final int TYPE_REGISTER = 6;
    private static final int TYPE_EMPTY = 7;

    // Number of non-empty types for aggregation (CODE, DATA, ENERGY, STRUCTURE, LABEL, LABELREF, REGISTER)
    private static final int NUM_NON_EMPTY_TYPES = 7;

    // Colors matching web minimap (RGB without alpha for TYPE_INT_RGB)
    private static final int[] CELL_COLORS = {
        0x3c5078,  // CODE - blue-gray
        0x32323c,  // DATA - dark gray
        0xffe664,  // ENERGY - yellow
        0xff7878,  // STRUCTURE - red/pink
        0xa0a0a8,  // LABEL - light gray
        0xa0a0a8,  // LABELREF - same as LABEL (distinguished by text color in detailed view)
        0x506080,  // REGISTER - medium blue-gray
        0x1e1e28   // EMPTY - dark background
    };

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

    // Persistent cell state using generation numbers (O(1) reset instead of O(worldSize) fill)
    private int[] cellTypes;        // [flatIndex] = type (valid only if generation matches)
    private int[] cellGenerations;  // [flatIndex] = generation when cell was set
    private int currentGeneration;  // Incremented on snapshot, invalidates all old values

    // Aggregation counts using generation numbers (O(1) reset instead of O(outputSize) fill)
    private int[] aggregationCounts;  // [pixel * NUM_NON_EMPTY_TYPES + type] = count
    private int[] pixelGenerations;   // [pixel] = generation when counts were initialized

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

        // Persistent state arrays
        int worldSize = worldWidth * worldHeight;
        this.cellTypes = new int[worldSize];
        this.cellGenerations = new int[worldSize];
        this.currentGeneration = 0;

        int outputSize = outputWidth * outputHeight;
        this.aggregationCounts = new int[outputSize * NUM_NON_EMPTY_TYPES];
        this.pixelGenerations = new int[outputSize];
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

        // O(1) reset: increment generation invalidates all old cell values
        currentGeneration++;

        // Process all cells in snapshot
        CellDataColumns columns = snapshot.getCellColumns();
        int cellCount = columns.getFlatIndicesCount();
        for (int i = 0; i < cellCount; i++) {
            int flatIndex = columns.getFlatIndices(i);
            int typeIndex = getCellTypeIndex(columns.getMoleculeData(i));

            cellTypes[flatIndex] = typeIndex;
            cellGenerations[flatIndex] = currentGeneration;

            if (typeIndex != TYPE_EMPTY) {
                updateAggregationForNewCell(flatIndex, typeIndex);
            }
        }

        this.lastSnapshot = snapshot;
        this.lastDelta = null;
    }

    @Override
    public void applyDeltaState(TickDelta delta) {
        ensureInitialized();

        // Process only changed cells
        CellDataColumns changed = delta.getChangedCells();
        int changedCount = changed.getFlatIndicesCount();
        for (int i = 0; i < changedCount; i++) {
            int flatIndex = changed.getFlatIndices(i);
            int newTypeIndex = getCellTypeIndex(changed.getMoleculeData(i));

            int oldTypeIndex = (cellGenerations[flatIndex] == currentGeneration)
                ? cellTypes[flatIndex]
                : TYPE_EMPTY;

            if (oldTypeIndex != newTypeIndex) {
                updateAggregationForTypeChange(flatIndex, oldTypeIndex, newTypeIndex);
            }

            cellTypes[flatIndex] = newTypeIndex;
            cellGenerations[flatIndex] = currentGeneration;
        }

        this.lastDelta = delta;
    }

    @Override
    public int[] renderCurrentState() {
        ensureInitialized();
        renderAggregatedCells();
        renderOrganismGlows(getOrganismsLazy());
        return frameBuffer;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Coordinate mapping (single source of truth)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Converts a world flat index to output pixel index.
     * Uses float math for consistent mapping between cells and organisms.
     */
    private int worldToPixelIndex(int flatIndex) {
        // Row-major: flatIndex = x * height + y
        int wx = flatIndex / worldHeight;
        int wy = flatIndex % worldHeight;
        return worldCoordsToPixelIndex(wx, wy);
    }

    /**
     * Converts world coordinates to output pixel index.
     */
    private int worldCoordsToPixelIndex(int wx, int wy) {
        int mx = (int) ((double) wx / worldWidth * outputWidth);
        int my = (int) ((double) wy / worldHeight * outputHeight);
        // Clamp to valid range (float rounding edge case)
        if (mx >= outputWidth) mx = outputWidth - 1;
        if (my >= outputHeight) my = outputHeight - 1;
        return my * outputWidth + mx;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Aggregation state management
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Ensures pixel aggregation counts are initialized for current generation.
     */
    private void ensurePixelInitialized(int pixelIdx) {
        if (pixelGenerations[pixelIdx] != currentGeneration) {
            int baseIdx = pixelIdx * NUM_NON_EMPTY_TYPES;
            for (int t = 0; t < NUM_NON_EMPTY_TYPES; t++) {
                aggregationCounts[baseIdx + t] = 0;
            }
            pixelGenerations[pixelIdx] = currentGeneration;
        }
    }

    /**
     * Updates aggregation counts when a new non-empty cell is added.
     */
    private void updateAggregationForNewCell(int flatIndex, int typeIndex) {
        int pixelIdx = worldToPixelIndex(flatIndex);
        ensurePixelInitialized(pixelIdx);
        aggregationCounts[pixelIdx * NUM_NON_EMPTY_TYPES + typeIndex]++;
    }

    /**
     * Updates aggregation counts when a cell type changes.
     */
    private void updateAggregationForTypeChange(int flatIndex, int oldType, int newType) {
        int pixelIdx = worldToPixelIndex(flatIndex);

        // Decrement old count if was non-empty and pixel is valid
        if (oldType != TYPE_EMPTY && pixelGenerations[pixelIdx] == currentGeneration) {
            aggregationCounts[pixelIdx * NUM_NON_EMPTY_TYPES + oldType]--;
        }

        // Increment new count if non-empty
        if (newType != TYPE_EMPTY) {
            ensurePixelInitialized(pixelIdx);
            aggregationCounts[pixelIdx * NUM_NON_EMPTY_TYPES + newType]++;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Rendering
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Renders aggregated cell colors to frame buffer using majority voting.
     */
    private void renderAggregatedCells() {
        int totalPixels = outputWidth * outputHeight;
        // Approximate cells per pixel for background weighting
        int cellsPerPixel = (worldWidth / outputWidth) * (worldHeight / outputHeight);

        for (int pixelIdx = 0; pixelIdx < totalPixels; pixelIdx++) {
            // Untouched pixels are empty
            if (pixelGenerations[pixelIdx] != currentGeneration) {
                frameBuffer[pixelIdx] = CELL_COLORS[TYPE_EMPTY];
                continue;
            }

            int baseIdx = pixelIdx * NUM_NON_EMPTY_TYPES;

            // Sum non-empty counts
            int nonEmptyTotal = 0;
            for (int t = 0; t < NUM_NON_EMPTY_TYPES; t++) {
                nonEmptyTotal += aggregationCounts[baseIdx + t];
            }

            // Background weighting (2.5% like server)
            int backgroundCells = cellsPerPixel - nonEmptyTotal;
            int weightedEmpty = backgroundCells / 40;

            // Majority vote
            int maxCount = weightedEmpty;
            int winningType = TYPE_EMPTY;
            for (int t = 0; t < NUM_NON_EMPTY_TYPES; t++) {
                int count = aggregationCounts[baseIdx + t];
                if (count > maxCount) {
                    maxCount = count;
                    winningType = t;
                }
            }

            frameBuffer[pixelIdx] = CELL_COLORS[winningType];
        }
    }

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
        return ORGANISM_PALETTE[genomeHashColorMap
                .computeIfAbsent(genomeHash, k -> genomeHashColorMap.size() % ORGANISM_PALETTE.length)];
    }

    /**
     * Adds glow density for an organism position, applying coordinate quantization if enabled.
     */
    private void addGlowDensity(int wx, int wy, int totalPixels) {
        if (clusterGrid > 1) {
            wx = (wx / clusterGrid) * clusterGrid;
            wy = (wy / clusterGrid) * clusterGrid;
        }
        int pixelIdx = worldCoordsToPixelIndex(wx, wy);
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

    private int getCellTypeIndex(int moleculeInt) {
        int moleculeType = moleculeInt & Config.TYPE_MASK;
        if (moleculeType == Config.TYPE_CODE) return TYPE_CODE;
        if (moleculeType == Config.TYPE_DATA) return TYPE_DATA;
        if (moleculeType == Config.TYPE_ENERGY) return TYPE_ENERGY;
        if (moleculeType == Config.TYPE_STRUCTURE) return TYPE_STRUCTURE;
        if (moleculeType == Config.TYPE_LABEL) return TYPE_LABEL;
        if (moleculeType == Config.TYPE_LABELREF) return TYPE_LABELREF;
        if (moleculeType == Config.TYPE_REGISTER) return TYPE_REGISTER;
        return TYPE_EMPTY;
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
