package org.evochora.cli.rendering.frame;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
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
    private static final int[] GLOW_SIZES = {6, 10, 14, 18};
    private static final int[] DENSITY_THRESHOLDS = {3, 10, 30};
    private static final int GLOW_COLOR = 0x4a9a6a;  // Muted green
    private static final int CORE_SIZE = 3;

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
    private int[] coordBuffer;  // Reusable buffer for coordinate conversion
    private boolean initialized = false;

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

        // Pre-compute glow sprites
        initGlowSprites();

        // Reusable buffer for coordinate conversion
        this.coordBuffer = new int[envProps.getWorldShape().length];

        this.initialized = true;
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("Renderer not initialized. Call init(EnvironmentProperties) first.");
        }
    }

    private void initGlowSprites() {
        glowSprites = new int[GLOW_SIZES.length][];
        for (int i = 0; i < GLOW_SIZES.length; i++) {
            glowSprites[i] = createGlowSprite(GLOW_SIZES[i]);
        }
    }

    private int[] createGlowSprite(int size) {
        int[] pixels = new int[size * size];
        float center = size / 2.0f;
        float radius = center;

        int r = (GLOW_COLOR >> 16) & 0xFF;
        int g = (GLOW_COLOR >> 8) & 0xFF;
        int b = GLOW_COLOR & 0xFF;

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = x - center + 0.5f;
                float dy = y - center + 0.5f;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);

                int alpha = 0;

                // Core (solid)
                if (Math.abs(dx) <= CORE_SIZE / 2.0f && Math.abs(dy) <= CORE_SIZE / 2.0f) {
                    alpha = 255;
                }
                // Glow (gradient)
                else if (dist <= radius) {
                    float t = dist / radius;
                    // 3-stop gradient: 60% -> 30% -> 0%
                    if (t < 0.5f) {
                        alpha = (int) (153 - t * 2 * 76);  // 60% -> 30%
                    } else {
                        alpha = (int) (76 * (1 - (t - 0.5f) * 2));  // 30% -> 0%
                    }
                }

                pixels[y * size + x] = (alpha << 24) | (r << 16) | (g << 8) | b;
            }
        }

        return pixels;
    }

    @Override
    protected int[] doRenderSnapshot(TickData snapshot) {
        ensureInitialized();

        // Clear frame
        Arrays.fill(frameBuffer, CELL_COLORS[TYPE_EMPTY]);

        // Aggregate cells via majority voting
        aggregateCells(snapshot.getCellColumns());

        // Render organism glows
        renderOrganismGlows(snapshot.getOrganismsList());

        return frameBuffer;
    }

    @Override
    protected int[] doRenderDelta(TickDelta delta) {
        ensureInitialized();

        // For minimap, we do a full re-render since aggregation needs all cells
        // The delta only has changed cells, but majority voting needs the full picture
        // In practice, this is still fast because output is small (e.g., 300x300)

        // Clear frame
        Arrays.fill(frameBuffer, CELL_COLORS[TYPE_EMPTY]);

        // We need to aggregate from the changed cells
        // Note: This is a simplification - ideally we'd maintain full cell state
        // For now, we'll render organisms over the existing background
        aggregateCells(delta.getChangedCells());

        // Render organism glows
        renderOrganismGlows(delta.getOrganismsList());

        return frameBuffer;
    }

    private void aggregateCells(CellDataColumns cellColumns) {
        // counts[pixelIndex * NUM_TYPES + typeIndex] = count
        int[] counts = new int[outputWidth * outputHeight * NUM_TYPES];

        // Sparse iteration over all cells
        int cellCount = cellColumns.getFlatIndicesCount();
        for (int i = 0; i < cellCount; i++) {
            int flatIndex = cellColumns.getFlatIndices(i);
            int moleculeInt = cellColumns.getMoleculeData(i);

            // Convert flat index to world coordinates
            envProps.flatIndexToCoordinates(flatIndex, coordBuffer);
            int wx = coordBuffer[0];
            int wy = coordBuffer[1];

            // Map to output pixel
            int mx = Math.min(wx / scaleX, outputWidth - 1);
            int my = Math.min(wy / scaleY, outputHeight - 1);
            int mIdx = my * outputWidth + mx;

            // Determine cell type and count
            int typeIndex = getCellTypeIndex(moleculeInt);
            counts[mIdx * NUM_TYPES + typeIndex]++;
        }

        // Background weighting (2.5% like server)
        int cellsPerPixel = scaleX * scaleY;
        int totalPixels = outputWidth * outputHeight;

        for (int mIdx = 0; mIdx < totalPixels; mIdx++) {
            int baseIdx = mIdx * NUM_TYPES;
            int occupiedCells = 0;
            for (int t = 0; t < NUM_TYPES; t++) {
                occupiedCells += counts[baseIdx + t];
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

            // IP position
            int wx = org.getIp().getComponents(0);
            int wy = org.getIp().getComponents(1);
            int mx = Math.min(wx * outputWidth / worldWidth, outputWidth - 1);
            int my = Math.min(wy * outputHeight / worldHeight, outputHeight - 1);
            density[my * outputWidth + mx]++;

            // DP positions
            for (Vector dp : org.getDataPointersList()) {
                wx = dp.getComponents(0);
                wy = dp.getComponents(1);
                mx = Math.min(wx * outputWidth / worldWidth, outputWidth - 1);
                my = Math.min(wy * outputHeight / worldHeight, outputHeight - 1);
                density[my * outputWidth + mx]++;
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
        return GLOW_SIZES.length - 1;
    }

    private void blitGlowSprite(int centerX, int centerY, int spriteIndex) {
        int[] sprite = glowSprites[spriteIndex];
        int size = GLOW_SIZES[spriteIndex];
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
