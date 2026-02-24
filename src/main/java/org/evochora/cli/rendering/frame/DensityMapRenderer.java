package org.evochora.cli.rendering.frame;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.List;

import org.evochora.cli.rendering.AbstractFrameRenderer;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.runtime.model.EnvironmentProperties;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Density heatmap renderer showing organism concentration as a Viridis colormap
 * overlaid on the environment cell composition.
 * <p>
 * Renders in two layers:
 * <ol>
 *   <li><strong>Background:</strong> Environment cell types via majority voting
 *       (delegated to {@link EnvironmentBackgroundLayer})</li>
 *   <li><strong>Foreground:</strong> Organism density heatmap using Viridis colormap,
 *       alpha-blended over the background</li>
 * </ol>
 * <p>
 * Density is computed per output pixel then spatially smoothed using a separable
 * box blur. This produces smooth "heat clouds" even when organisms are sparsely
 * distributed.
 * <p>
 * <strong>CLI Usage:</strong>
 * <pre>
 *   evochora video density --scale 0.3 --blur-radius 5 --overlay info -o density.mkv
 *   evochora video density --scale 0.5 --count-dps --blur-radius 8 -o density.mkv
 * </pre>
 * <p>
 * <strong>Thread Safety:</strong> Not thread-safe. Use one renderer per thread.
 */
@Command(name = "density", description = "Density heatmap rendering with Viridis colormap over environment background",
         mixinStandardHelpOptions = true)
public class DensityMapRenderer extends AbstractFrameRenderer {

    @Option(names = "--scale",
            description = "Fraction of world size (0 < scale < 1, default: ${DEFAULT-VALUE})",
            defaultValue = "0.3")
    private double scale;

    @Option(names = "--count-dps",
            description = "Count data pointer positions in addition to instruction pointers (default: ${DEFAULT-VALUE})",
            defaultValue = "false")
    private boolean countDps;

    @Option(names = "--blur-radius",
            description = "Blur kernel radius in output pixels (default: ${DEFAULT-VALUE})",
            defaultValue = "5")
    private int blurRadius;

    /**
     * Viridis colormap lookup table (256 entries, RGB packed as int).
     */
    private static final int[] VIRIDIS_LUT = generateViridisLUT();

    // Dimensions
    private int outputWidth;
    private int outputHeight;

    // Frame buffer
    private BufferedImage frame;
    private int[] frameBuffer;

    // Environment background layer (cell aggregation + rendering)
    private EnvironmentBackgroundLayer background;

    // Density state
    private int[] densityGrid;      // raw organism counts per output pixel
    private int[] blurredDensity;   // after box blur
    private int[] blurTemp;         // temporary buffer for separable blur
    private int maxBlurred;         // running maximum (monotonically increasing)

    // Overlay support for sampling mode
    private TickData lastSnapshot;
    private TickDelta lastDelta;

    // True when renderCurrentState() is called from doRenderSnapshot/doRenderDelta
    // (overlays are applied by AbstractFrameRenderer's template method in that case)
    private boolean calledFromTemplate;

    private boolean initialized = false;

    /**
     * Default constructor for PicoCLI instantiation.
     */
    public DensityMapRenderer() {
        // Options populated by PicoCLI
    }

    @Override
    public void init(EnvironmentProperties envProps) {
        if (scale <= 0 || scale >= 1) {
            throw new IllegalArgumentException(
                    "Density map scale must be between 0 and 1 (exclusive), got: " + scale);
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

        // Environment background
        this.background = new EnvironmentBackgroundLayer(worldWidth, worldHeight, outputWidth, outputHeight);

        // Density state
        int outputSize = outputWidth * outputHeight;
        this.densityGrid = new int[outputSize];
        this.blurredDensity = new int[outputSize];
        this.blurTemp = new int[outputSize];
        this.maxBlurred = 0;

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
        buildDensityGrid(snapshot.getOrganismsList());
        this.lastSnapshot = snapshot;
        this.lastDelta = null;
    }

    @Override
    public void applyDeltaState(TickDelta delta) {
        ensureInitialized();
        background.processDeltaCells(delta.getChangedCells());
        buildDensityGrid(delta.getOrganismsList());
        this.lastDelta = delta;
    }

    @Override
    public int[] renderCurrentState() {
        ensureInitialized();

        // Layer 1: Cell background
        background.renderTo(frameBuffer);

        // Layer 2: Density heatmap alpha-blended on top
        renderDensityOverlay();

        // Layer 3: Overlays (only in sampling mode — when called from template methods,
        // AbstractFrameRenderer applies overlays after doRenderSnapshot/doRenderDelta)
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
    // Density computation
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Builds the density grid from organism positions and applies box blur.
     *
     * @param organisms List of organisms from snapshot or delta.
     */
    private void buildDensityGrid(List<OrganismState> organisms) {
        Arrays.fill(densityGrid, 0);

        int totalPixels = densityGrid.length;
        for (OrganismState org : organisms) {
            if (org.getIsDead()) continue;

            int pixelIdx = background.worldCoordsToPixelIndex(
                    org.getIp().getComponents(0),
                    org.getIp().getComponents(1));
            if (pixelIdx >= 0 && pixelIdx < totalPixels) {
                densityGrid[pixelIdx]++;
            }

            if (countDps) {
                for (Vector dp : org.getDataPointersList()) {
                    int dpIdx = background.worldCoordsToPixelIndex(
                            dp.getComponents(0),
                            dp.getComponents(1));
                    if (dpIdx >= 0 && dpIdx < totalPixels) {
                        densityGrid[dpIdx]++;
                    }
                }
            }
        }

        // Apply separable box blur
        boxBlur(densityGrid, blurredDensity, blurTemp, outputWidth, outputHeight, blurRadius);

        // Update running maximum
        for (int v : blurredDensity) {
            if (v > maxBlurred) {
                maxBlurred = v;
            }
        }
    }

    /**
     * Separable box blur (sum, not average) using sliding window.
     * O(pixels) regardless of radius.
     *
     * @param src    Source array (raw counts).
     * @param dst    Destination array (blurred result).
     * @param temp   Temporary buffer for intermediate horizontal pass.
     * @param width  Grid width.
     * @param height Grid height.
     * @param radius Blur radius in pixels.
     */
    static void boxBlur(int[] src, int[] dst, int[] temp,
                        int width, int height, int radius) {
        if (radius <= 0) {
            System.arraycopy(src, 0, dst, 0, src.length);
            return;
        }

        // Horizontal pass: src → temp
        for (int y = 0; y < height; y++) {
            int rowOffset = y * width;
            int sum = 0;

            // Seed window [0, radius]
            for (int x = 0; x <= radius && x < width; x++) {
                sum += src[rowOffset + x];
            }

            for (int x = 0; x < width; x++) {
                temp[rowOffset + x] = sum;

                // Expand right edge
                int addX = x + radius + 1;
                if (addX < width) sum += src[rowOffset + addX];

                // Shrink left edge
                int removeX = x - radius;
                if (removeX >= 0) sum -= src[rowOffset + removeX];
            }
        }

        // Vertical pass: temp → dst
        for (int x = 0; x < width; x++) {
            int sum = 0;

            // Seed window [0, radius]
            for (int y = 0; y <= radius && y < height; y++) {
                sum += temp[y * width + x];
            }

            for (int y = 0; y < height; y++) {
                dst[y * width + x] = sum;

                int addY = y + radius + 1;
                if (addY < height) sum += temp[addY * width + x];

                int removeY = y - radius;
                if (removeY >= 0) sum -= temp[removeY * width + x];
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Density overlay rendering
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Alpha-blends the density heatmap over the cell background.
     */
    private void renderDensityOverlay() {
        if (maxBlurred == 0) return;

        int totalPixels = outputWidth * outputHeight;
        for (int i = 0; i < totalPixels; i++) {
            int blurred = blurredDensity[i];
            if (blurred == 0) continue;

            // Map density to Viridis color index (1-255, skip 0 for transparency)
            int colorIndex = (blurred * 255) / maxBlurred;
            if (colorIndex > 255) colorIndex = 255;
            if (colorIndex < 1) colorIndex = 1;

            // Alpha: proportional to density (max 230 so background peeks through)
            int alpha = (blurred * 230) / maxBlurred;
            if (alpha > 230) alpha = 230;

            // Alpha blend Viridis color over cell background
            int viridis = VIRIDIS_LUT[colorIndex];
            int bg = frameBuffer[i];

            int invA = 255 - alpha;
            int r = (((viridis >> 16) & 0xFF) * alpha + ((bg >> 16) & 0xFF) * invA) / 255;
            int g = (((viridis >> 8) & 0xFF) * alpha + ((bg >> 8) & 0xFF) * invA) / 255;
            int b = ((viridis & 0xFF) * alpha + (bg & 0xFF) * invA) / 255;

            frameBuffer[i] = (r << 16) | (g << 8) | b;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Viridis colormap
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Generates a 256-entry Viridis colormap lookup table.
     * Uses linear interpolation between control points sampled from the
     * standard matplotlib Viridis colormap.
     *
     * @return Array of 256 RGB-packed int values.
     */
    private static int[] generateViridisLUT() {
        int[][] cp = {
            {0,   68,  1,   84},
            {16,  72,  26,  108},
            {32,  68,  50,  126},
            {48,  58,  72,  138},
            {64,  49,  92,  142},
            {80,  38,  112, 142},
            {96,  31,  129, 141},
            {112, 30,  148, 134},
            {128, 34,  162, 126},
            {144, 56,  176, 113},
            {160, 86,  188, 97},
            {176, 122, 198, 78},
            {192, 160, 206, 57},
            {208, 199, 212, 39},
            {224, 232, 217, 35},
            {240, 252, 225, 34},
            {255, 253, 231, 37}
        };

        int[] lut = new int[256];
        int cpIdx = 0;

        for (int i = 0; i < 256; i++) {
            while (cpIdx < cp.length - 2 && cp[cpIdx + 1][0] <= i) {
                cpIdx++;
            }

            float t = (float) (i - cp[cpIdx][0]) / (cp[cpIdx + 1][0] - cp[cpIdx][0]);
            t = Math.max(0, Math.min(1, t));

            int r = Math.round(cp[cpIdx][1] + (cp[cpIdx + 1][1] - cp[cpIdx][1]) * t);
            int g = Math.round(cp[cpIdx][2] + (cp[cpIdx + 1][2] - cp[cpIdx][2]) * t);
            int b = Math.round(cp[cpIdx][3] + (cp[cpIdx + 1][3] - cp[cpIdx][3]) * t);

            lut[i] = (r << 16) | (g << 8) | b;
        }

        return lut;
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
