package org.evochora.cli.rendering.frame;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    // Cell type constants
    private static final int TYPE_CODE = 0;
    private static final int TYPE_DATA = 1;
    private static final int TYPE_ENERGY = 2;
    private static final int TYPE_STRUCTURE = 3;
    private static final int TYPE_LABEL = 4;
    private static final int TYPE_EMPTY = 5;
    private static final int NUM_TYPES = 6;

    // Colors matching web minimap (RGB without alpha for TYPE_INT_RGB)
    private static final int[] CELL_COLORS = {
        0x3c5078,  // CODE - blue-gray
        0x32323c,  // DATA - dark gray
        0xffe664,  // ENERGY - yellow
        0xff7878,  // STRUCTURE - red/pink
        0xa0a0a8,  // LABEL - light gray
        0x1e1e28   // EMPTY - dark background
    };

    // Glow configuration (matching web minimap)
    // Base sizes for ~400px wide output, scaled up for larger outputs
    private static final int[] BASE_GLOW_SIZES = {6, 10, 14, 18};
    private static final int[] DENSITY_THRESHOLDS = {3, 10, 30};
    private static final int GLOW_COLOR = 0x4a9a6a;  // Muted green
    private static final int BASE_CORE_SIZE = 3;
    private static final int BASE_OUTPUT_WIDTH = 400;  // Reference width for glow scaling

    // Initialized after CLI parsing via init()
    private int worldWidth;
    private int worldHeight;
    private int outputWidth;
    private int outputHeight;
    private int scaleX;  // World cells per output pixel (X)
    private int scaleY;  // World cells per output pixel (Y)
    private BufferedImage frame;
    private int[] frameBuffer;
    private int[][] glowSprites;
    private int[] glowSizes;     // Scaled glow sizes for current output resolution
    private int coreSize;        // Scaled core size
    private int[] coordBuffer;   // Reusable buffer for coordinate conversion
    private boolean initialized = false;

    // Persistent cell state for incremental rendering using generation numbers
    // This eliminates O(12M) Arrays.fill per frame!
    private int[] cellTypes;        // [flatIndex] = type (only valid if cellGenerations[flatIndex] == currentGeneration)
    private int[] cellGenerations;  // [flatIndex] = generation when this cell was last set
    private int currentGeneration;  // Incremented on each snapshot, invalidates old values

    // Lazy organism access - only deserialize when actually rendering!
    // This is crucial for sampling mode where we apply many ticks but only render few.
    private TickData lastSnapshot;   // For lazy organism access
    private TickDelta lastDelta;     // For lazy organism access (overrides snapshot if set)

    // Persistent aggregation state using generation numbers (eliminates O(6M) Arrays.fill!)
    // Only stores non-empty types (CODE=0, DATA=1, ENERGY=2, STRUCTURE=3, LABEL=4)
    private static final int NUM_NON_EMPTY_TYPES = 5;
    private int[] aggregationCounts;  // [outputPixel * NUM_NON_EMPTY_TYPES + type] for types 0-4
    private int[] pixelGenerations;   // [outputPixel] = generation when pixel counts were set
    private boolean aggregationValid;  // True if generation-based aggregation is active

    /**
     * Default constructor for PicoCLI instantiation.
     */
    public MinimapFrameRenderer() {
        // Options populated by PicoCLI
    }

    /**
     * Initializes the renderer with environment properties.
     * <p>
     * Must be called after PicoCLI has parsed options and before rendering.
     *
     * @param envProps Environment properties (world shape, topology).
     * @throws IllegalArgumentException if scale is not between 0 and 1 (exclusive).
     */
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

        // Scale factors for coordinate mapping (world cells per output pixel)
        this.scaleX = Math.max(1, worldWidth / outputWidth);
        this.scaleY = Math.max(1, worldHeight / outputHeight);

        // Frame buffer
        this.frame = new BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_RGB);
        this.frameBuffer = ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();

        // Scale glow sizes based on output resolution
        // At BASE_OUTPUT_WIDTH (400px), use base sizes. Scale proportionally for other sizes.
        double glowScale = (double) outputWidth / BASE_OUTPUT_WIDTH;
        this.glowSizes = new int[BASE_GLOW_SIZES.length];
        for (int i = 0; i < BASE_GLOW_SIZES.length; i++) {
            this.glowSizes[i] = Math.max(2, (int) (BASE_GLOW_SIZES[i] * glowScale));
        }
        this.coreSize = Math.max(1, (int) (BASE_CORE_SIZE * glowScale));

        // Pre-compute glow sprites with scaled sizes
        initGlowSprites();

        // Reusable buffer for coordinate conversion
        this.coordBuffer = new int[envProps.getWorldShape().length];

        // Persistent cell state with generation numbers (no Arrays.fill needed!)
        int worldSize = worldWidth * worldHeight;
        this.cellTypes = new int[worldSize];
        this.cellGenerations = new int[worldSize];  // All start at 0, currentGeneration starts at 1
        this.currentGeneration = 0;
        this.lastSnapshot = null;
        this.lastDelta = null;

        // Persistent aggregation state with generation numbers (no Arrays.fill needed!)
        int outputSize = outputWidth * outputHeight;
        this.aggregationCounts = new int[outputSize * NUM_NON_EMPTY_TYPES];
        this.pixelGenerations = new int[outputSize];  // All start at 0
        this.aggregationValid = false;

        this.initialized = true;
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("Renderer not initialized. Call init(EnvironmentProperties) first.");
        }
    }

    private void initGlowSprites() {
        glowSprites = new int[glowSizes.length][];
        for (int i = 0; i < glowSizes.length; i++) {
            glowSprites[i] = createGlowSprite(glowSizes[i]);
        }
    }

    private int[] createGlowSprite(int size) {
        int[] pixels = new int[size * size];
        float center = size / 2.0f;
        float glowRadius = center;
        float coreRadius = coreSize / 2.0f;

        int r = (GLOW_COLOR >> 16) & 0xFF;
        int g = (GLOW_COLOR >> 8) & 0xFF;
        int b = GLOW_COLOR & 0xFF;

        // Match web version: radial gradient from coreRadius to glowRadius
        // with 3 stops: 60% at core edge, 30% at middle, 0% at outer edge
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = x - center + 0.5f;
                float dy = y - center + 0.5f;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);

                int alpha;

                if (dist <= coreRadius) {
                    // Inside core radius: start of gradient (60% opacity)
                    // This creates smooth transition at core edge
                    alpha = 153;  // 60%
                } else if (dist <= glowRadius) {
                    // Gradient zone: coreRadius to glowRadius
                    // t=0 at coreRadius (60%), t=0.5 middle (30%), t=1 at glowRadius (0%)
                    float t = (dist - coreRadius) / (glowRadius - coreRadius);
                    if (t < 0.5f) {
                        alpha = (int) (153 - t * 2 * (153 - 76));  // 60% -> 30%
                    } else {
                        alpha = (int) (76 * (1 - (t - 0.5f) * 2));  // 30% -> 0%
                    }
                } else {
                    alpha = 0;
                }

                pixels[y * size + x] = (alpha << 24) | (r << 16) | (g << 8) | b;
            }
        }

        // Draw solid square core on top (like web version)
        int coreStart = (int) (center - coreRadius);
        int coreEnd = (int) (center + coreRadius);
        int solidColor = (255 << 24) | (r << 16) | (g << 8) | b;
        for (int y = coreStart; y < coreEnd; y++) {
            for (int x = coreStart; x < coreEnd; x++) {
                if (y >= 0 && y < size && x >= 0 && x < size) {
                    pixels[y * size + x] = solidColor;
                }
            }
        }

        return pixels;
    }

    @Override
    protected int[] doRenderSnapshot(TickData snapshot) {
        ensureInitialized();

        // O(1) reset via generation increment (no Arrays.fill needed!)
        currentGeneration++;

        // Update cell types AND build aggregation counts in one pass
        // This is O(cells_in_snapshot), NOT O(all_world_cells)!
        CellDataColumns columns = snapshot.getCellColumns();
        int cellCount = columns.getFlatIndicesCount();
        for (int i = 0; i < cellCount; i++) {
            int flatIndex = columns.getFlatIndices(i);
            int moleculeInt = columns.getMoleculeData(i);
            int typeIndex = getCellTypeIndex(moleculeInt);

            cellTypes[flatIndex] = typeIndex;
            cellGenerations[flatIndex] = currentGeneration;

            // Build aggregation counts for non-empty types
            if (typeIndex != TYPE_EMPTY) {
                // Row-major: flatIndex = x * height + y, so x = flatIndex / height, y = flatIndex % height
                int wx = flatIndex / worldHeight;
                int wy = flatIndex % worldHeight;
                // Use float math for consistent mapping (same as organism positions)
                int mx = (int)((double)wx / worldWidth * outputWidth);
                int my = (int)((double)wy / worldHeight * outputHeight);
                if (mx >= outputWidth) mx = outputWidth - 1;
                if (my >= outputHeight) my = outputHeight - 1;
                int mIdx = my * outputWidth + mx;

                if (pixelGenerations[mIdx] != currentGeneration) {
                    int baseIdx = mIdx * NUM_NON_EMPTY_TYPES;
                    for (int t = 0; t < NUM_NON_EMPTY_TYPES; t++) {
                        aggregationCounts[baseIdx + t] = 0;
                    }
                    pixelGenerations[mIdx] = currentGeneration;
                }
                aggregationCounts[mIdx * NUM_NON_EMPTY_TYPES + typeIndex]++;
            }
        }

        // Store for lazy organism access at render time
        this.lastSnapshot = snapshot;
        this.lastDelta = null;

        // Enable fast-path aggregation
        this.aggregationValid = true;

        // Render from persistent state (organisms accessed lazily there)
        return renderCurrentState();
    }

    @Override
    protected int[] doRenderDelta(TickDelta delta) {
        ensureInitialized();

        // Update only changed cells AND update aggregation counts incrementally
        CellDataColumns changed = delta.getChangedCells();
        int changedCount = changed.getFlatIndicesCount();
        for (int i = 0; i < changedCount; i++) {
            int flatIndex = changed.getFlatIndices(i);
            int moleculeInt = changed.getMoleculeData(i);
            int newTypeIndex = getCellTypeIndex(moleculeInt);

            // Get old type using generation check
            int oldTypeIndex = (cellGenerations[flatIndex] == currentGeneration)
                ? cellTypes[flatIndex]
                : TYPE_EMPTY;

            // Update aggregation counts if type changed
            if (aggregationValid && oldTypeIndex != newTypeIndex) {
                // Row-major: flatIndex = x * height + y, so x = flatIndex / height, y = flatIndex % height
                int wx = flatIndex / worldHeight;
                int wy = flatIndex % worldHeight;
                // Use float math for consistent mapping (same as organism positions)
                int mx = (int)((double)wx / worldWidth * outputWidth);
                int my = (int)((double)wy / worldHeight * outputHeight);
                if (mx >= outputWidth) mx = outputWidth - 1;
                if (my >= outputHeight) my = outputHeight - 1;
                int mIdx = my * outputWidth + mx;

                // Decrement old count if was non-empty
                if (oldTypeIndex != TYPE_EMPTY && pixelGenerations[mIdx] == currentGeneration) {
                    aggregationCounts[mIdx * NUM_NON_EMPTY_TYPES + oldTypeIndex]--;
                }

                // Increment new count if non-empty
                if (newTypeIndex != TYPE_EMPTY) {
                    if (pixelGenerations[mIdx] != currentGeneration) {
                        int baseIdx = mIdx * NUM_NON_EMPTY_TYPES;
                        for (int t = 0; t < NUM_NON_EMPTY_TYPES; t++) {
                            aggregationCounts[baseIdx + t] = 0;
                        }
                        pixelGenerations[mIdx] = currentGeneration;
                    }
                    aggregationCounts[mIdx * NUM_NON_EMPTY_TYPES + newTypeIndex]++;
                }
            }

            cellTypes[flatIndex] = newTypeIndex;
            cellGenerations[flatIndex] = currentGeneration;
        }

        // Store for lazy organism access at render time
        this.lastDelta = delta;

        // Render from persistent state (organisms accessed lazily there)
        return renderCurrentState();
    }

    /**
     * Applies snapshot state WITHOUT rendering.
     * Uses generation numbers to avoid O(12M) Arrays.fill - just increment generation (O(1))!
     * Also builds aggregation counts incrementally for touched pixels only.
     * IMPORTANT: Does NOT access organismsList - that's expensive and only needed at render time!
     */
    @Override
    public void applySnapshotState(TickData snapshot) {
        ensureInitialized();

        // O(1) reset: increment generation, all old values become invalid
        currentGeneration++;

        // Update cell types from snapshot AND build aggregation counts
        // Only touches cells that are actually in the snapshot (typically << worldSize)
        CellDataColumns columns = snapshot.getCellColumns();
        int cellCount = columns.getFlatIndicesCount();
        for (int i = 0; i < cellCount; i++) {
            int flatIndex = columns.getFlatIndices(i);
            int moleculeInt = columns.getMoleculeData(i);
            int typeIndex = getCellTypeIndex(moleculeInt);

            // Store cell type with current generation
            cellTypes[flatIndex] = typeIndex;
            cellGenerations[flatIndex] = currentGeneration;

            // Update aggregation counts for non-empty types
            if (typeIndex != TYPE_EMPTY) {
                // Row-major: flatIndex = x * height + y, so x = flatIndex / height, y = flatIndex % height
                int wx = flatIndex / worldHeight;
                int wy = flatIndex % worldHeight;
                // Use float math for consistent mapping (same as organism positions)
                int mx = (int)((double)wx / worldWidth * outputWidth);
                int my = (int)((double)wy / worldHeight * outputHeight);
                if (mx >= outputWidth) mx = outputWidth - 1;
                if (my >= outputHeight) my = outputHeight - 1;
                int mIdx = my * outputWidth + mx;

                // Initialize pixel counts if first touch this generation
                if (pixelGenerations[mIdx] != currentGeneration) {
                    int baseIdx = mIdx * NUM_NON_EMPTY_TYPES;
                    for (int t = 0; t < NUM_NON_EMPTY_TYPES; t++) {
                        aggregationCounts[baseIdx + t] = 0;
                    }
                    pixelGenerations[mIdx] = currentGeneration;
                }

                // Increment count for this type (typeIndex 0-4 maps directly)
                aggregationCounts[mIdx * NUM_NON_EMPTY_TYPES + typeIndex]++;
            }
        }

        // Store snapshot reference for organism access at render time (lazy)
        this.lastSnapshot = snapshot;
        this.lastDelta = null;
        this.aggregationValid = true;
    }

    /**
     * Applies delta state WITHOUT rendering.
     * Uses generation numbers to correctly identify old cell types.
     * Uses incremental aggregation update - only touches changed cells.
     * IMPORTANT: Does NOT access organismsList - that's expensive and only needed at render time!
     */
    @Override
    public void applyDeltaState(TickDelta delta) {
        ensureInitialized();

        // Update only changed cells in persistent state with incremental aggregation
        CellDataColumns changed = delta.getChangedCells();
        int changedCount = changed.getFlatIndicesCount();
        for (int i = 0; i < changedCount; i++) {
            int flatIndex = changed.getFlatIndices(i);
            int moleculeInt = changed.getMoleculeData(i);
            int newTypeIndex = getCellTypeIndex(moleculeInt);

            // Get old type using generation check (if generation doesn't match, it's TYPE_EMPTY)
            int oldTypeIndex = (cellGenerations[flatIndex] == currentGeneration)
                ? cellTypes[flatIndex]
                : TYPE_EMPTY;

            // Only update aggregation if type actually changed
            if (aggregationValid && oldTypeIndex != newTypeIndex) {
                // Row-major: flatIndex = x * height + y, so x = flatIndex / height, y = flatIndex % height
                int wx = flatIndex / worldHeight;
                int wy = flatIndex % worldHeight;
                // Use float math for consistent mapping (same as organism positions)
                int mx = (int)((double)wx / worldWidth * outputWidth);
                int my = (int)((double)wy / worldHeight * outputHeight);
                if (mx >= outputWidth) mx = outputWidth - 1;
                if (my >= outputHeight) my = outputHeight - 1;
                int mIdx = my * outputWidth + mx;

                // Decrement old count if was non-empty and pixel is valid
                if (oldTypeIndex != TYPE_EMPTY && pixelGenerations[mIdx] == currentGeneration) {
                    aggregationCounts[mIdx * NUM_NON_EMPTY_TYPES + oldTypeIndex]--;
                }

                // Increment new count if non-empty
                if (newTypeIndex != TYPE_EMPTY) {
                    // Initialize pixel if first touch this generation
                    if (pixelGenerations[mIdx] != currentGeneration) {
                        int baseIdx = mIdx * NUM_NON_EMPTY_TYPES;
                        for (int t = 0; t < NUM_NON_EMPTY_TYPES; t++) {
                            aggregationCounts[baseIdx + t] = 0;
                        }
                        pixelGenerations[mIdx] = currentGeneration;
                    }
                    aggregationCounts[mIdx * NUM_NON_EMPTY_TYPES + newTypeIndex]++;
                }
            }

            // Store new cell type with current generation
            cellTypes[flatIndex] = newTypeIndex;
            cellGenerations[flatIndex] = currentGeneration;
        }

        // Store delta reference for lazy organism access at render time
        this.lastDelta = delta;
    }

    /**
     * Renders the current frame from pre-computed aggregation counts.
     * This is O(outputPixels) instead of O(worldCells) when aggregationValid is true.
     * Organisms are accessed lazily here (only at render time, not during apply!).
     */
    @Override
    public int[] renderCurrentState() {
        ensureInitialized();

        if (aggregationValid) {
            // Fast path: use pre-computed aggregation counts
            renderFromAggregationCounts();
        } else {
            // Slow path: recompute aggregation (fallback)
            Arrays.fill(frameBuffer, CELL_COLORS[TYPE_EMPTY]);
            aggregateCellsFromState();
        }

        // Lazy organism access - only deserialize at render time!
        // Delta organisms override snapshot organisms (delta has current state)
        List<OrganismState> organisms = (lastDelta != null)
            ? lastDelta.getOrganismsList()
            : (lastSnapshot != null ? lastSnapshot.getOrganismsList() : List.of());

        // Render organism glows
        renderOrganismGlows(organisms);

        return frameBuffer;
    }

    /**
     * Fast rendering using pre-computed aggregation counts with generation numbers.
     * O(outputPixels) instead of O(worldCells).
     * Uses generation check to determine if pixel has any non-empty cells.
     */
    private void renderFromAggregationCounts() {
        int totalPixels = outputWidth * outputHeight;
        int cellsPerPixel = scaleX * scaleY;

        for (int mIdx = 0; mIdx < totalPixels; mIdx++) {
            // If pixel was never touched this generation, it's all empty
            if (pixelGenerations[mIdx] != currentGeneration) {
                frameBuffer[mIdx] = CELL_COLORS[TYPE_EMPTY];
                continue;
            }

            int baseIdx = mIdx * NUM_NON_EMPTY_TYPES;

            // Sum non-empty counts
            int nonEmptyTotal = 0;
            for (int t = 0; t < NUM_NON_EMPTY_TYPES; t++) {
                nonEmptyTotal += aggregationCounts[baseIdx + t];
            }

            // Background weighting (2.5% like server)
            int backgroundCells = cellsPerPixel - nonEmptyTotal;
            int weightedEmpty = backgroundCells / 40;

            // Majority vote → pixel color (start with empty as baseline)
            int maxCount = weightedEmpty;
            int winningType = TYPE_EMPTY;

            for (int t = 0; t < NUM_NON_EMPTY_TYPES; t++) {
                int count = aggregationCounts[baseIdx + t];
                if (count > maxCount) {
                    maxCount = count;
                    winningType = t;
                }
            }

            frameBuffer[mIdx] = CELL_COLORS[winningType];
        }
    }

    /**
     * Aggregates cells from persistent cellTypes[] state to output pixels via majority voting.
     * Uses generation numbers to determine valid cell types.
     * This is the slow fallback path - O(worldCells).
     */
    private void aggregateCellsFromState() {
        // counts[pixelIndex * NUM_TYPES + typeIndex] = count
        int[] counts = new int[outputWidth * outputHeight * NUM_TYPES];

        // Iterate over all world cells and aggregate to output pixels
        // Use generation check to get actual cell type
        for (int wy = 0; wy < worldHeight; wy++) {
            // Use float math for consistent mapping (same as organism positions)
            int my = (int)((double)wy / worldHeight * outputHeight);
            if (my >= outputHeight) my = outputHeight - 1;
            for (int wx = 0; wx < worldWidth; wx++) {
                int mx = (int)((double)wx / worldWidth * outputWidth);
                if (mx >= outputWidth) mx = outputWidth - 1;
                int mIdx = my * outputWidth + mx;

                // Row-major: flatIndex = x * height + y
                int flatIndex = wx * worldHeight + wy;
                // Generation check: if not current generation, treat as TYPE_EMPTY
                int typeIndex = (cellGenerations[flatIndex] == currentGeneration)
                    ? cellTypes[flatIndex]
                    : TYPE_EMPTY;
                counts[mIdx * NUM_TYPES + typeIndex]++;
            }
        }

        // Background weighting (2.5% like server)
        int cellsPerPixel = scaleX * scaleY;
        int totalPixels = outputWidth * outputHeight;

        for (int mIdx = 0; mIdx < totalPixels; mIdx++) {
            int baseIdx = mIdx * NUM_TYPES;
            int occupiedCells = 0;
            for (int t = 0; t < NUM_TYPES; t++) {
                if (t != TYPE_EMPTY) {
                    occupiedCells += counts[baseIdx + t];
                }
            }
            int backgroundCells = cellsPerPixel - occupiedCells;
            int weightedEmpty = backgroundCells / 40;  // 2.5% weight
            counts[baseIdx + TYPE_EMPTY] += weightedEmpty;
        }

        // Majority vote → pixel color
        for (int mIdx = 0; mIdx < totalPixels; mIdx++) {
            int baseIdx = mIdx * NUM_TYPES;
            int maxCount = 0;
            int winningType = TYPE_EMPTY;

            for (int t = 0; t < NUM_TYPES; t++) {
                if (counts[baseIdx + t] > maxCount) {
                    maxCount = counts[baseIdx + t];
                    winningType = t;
                }
            }

            frameBuffer[mIdx] = CELL_COLORS[winningType];
        }
    }

    private int getCellTypeIndex(int moleculeInt) {
        int moleculeType = moleculeInt & Config.TYPE_MASK;

        if (moleculeType == Config.TYPE_CODE) {
            return TYPE_CODE;
        } else if (moleculeType == Config.TYPE_DATA) {
            return TYPE_DATA;
        } else if (moleculeType == Config.TYPE_ENERGY) {
            return TYPE_ENERGY;
        } else if (moleculeType == Config.TYPE_STRUCTURE) {
            return TYPE_STRUCTURE;
        } else if (moleculeType == Config.TYPE_LABEL) {
            return TYPE_LABEL;
        } else {
            return TYPE_EMPTY;
        }
    }

    private void renderOrganismGlows(List<OrganismState> organisms) {
        // Density grid
        int[] density = new int[outputWidth * outputHeight];

        for (OrganismState org : organisms) {
            if (org.getIsDead()) continue;

            // IP position - use float math like web version: floor(wx / worldWidth * outputWidth)
            int wx = org.getIp().getComponents(0);
            int wy = org.getIp().getComponents(1);
            int mx = (int) ((double) wx / worldWidth * outputWidth);
            int my = (int) ((double) wy / worldHeight * outputHeight);
            // Bounds check
            if (mx >= 0 && mx < outputWidth && my >= 0 && my < outputHeight) {
                density[my * outputWidth + mx]++;
            }

            // DP positions
            for (Vector dp : org.getDataPointersList()) {
                wx = dp.getComponents(0);
                wy = dp.getComponents(1);
                mx = (int) ((double) wx / worldWidth * outputWidth);
                my = (int) ((double) wy / worldHeight * outputHeight);
                if (mx >= 0 && mx < outputWidth && my >= 0 && my < outputHeight) {
                    density[my * outputWidth + mx]++;
                }
            }
        }

        // Render glows
        for (int my = 0; my < outputHeight; my++) {
            for (int mx = 0; mx < outputWidth; mx++) {
                int count = density[my * outputWidth + mx];
                if (count == 0) continue;

                int spriteIndex = selectSpriteIndex(count);
                blitGlowSprite(mx, my, spriteIndex);
            }
        }
    }

    private int selectSpriteIndex(int count) {
        for (int i = 0; i < DENSITY_THRESHOLDS.length; i++) {
            if (count <= DENSITY_THRESHOLDS[i]) return i;
        }
        return glowSizes.length - 1;
    }

    private void blitGlowSprite(int centerX, int centerY, int spriteIndex) {
        int[] sprite = glowSprites[spriteIndex];
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
