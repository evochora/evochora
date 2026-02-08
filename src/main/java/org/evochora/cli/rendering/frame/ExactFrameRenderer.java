package org.evochora.cli.rendering.frame;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
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
 * Exact frame renderer - renders one pixel block per cell.
 * <p>
 * This renderer produces high-fidelity output where each simulation cell
 * is represented by a square of {@code scale × scale} pixels. It shows
 * detailed organism markers including direction triangles for instruction
 * pointers and squares for data pointers.
 * <p>
 * <strong>Performance:</strong> Supports optimized sampling mode via
 * {@link #applySnapshotState}/{@link #applyDeltaState} for efficient
 * rendering with --sampling-interval > 1.
 * <p>
 * <strong>CLI Usage:</strong>
 * <pre>
 *   evochora video exact --scale 4 --overlay info -o video.mkv
 * </pre>
 * <p>
 * <strong>Thread Safety:</strong> Not thread-safe. Use one renderer per thread.
 */
@Command(name = "exact", description = "Exact pixel-per-cell rendering with detailed organism markers",
         mixinStandardHelpOptions = true)
public class ExactFrameRenderer extends AbstractFrameRenderer {

    @Option(names = "--scale",
            description = "Pixels per cell (default: ${DEFAULT-VALUE})",
            defaultValue = "4")
    private int scale;

    // Colors (RGB with full alpha for TYPE_INT_RGB)
    private static final int COLOR_EMPTY = 0x000000;
    private static final int COLOR_CODE = 0x3c5078;
    private static final int COLOR_DATA = 0x32323c;
    private static final int COLOR_STRUCTURE = 0xff7878;
    private static final int COLOR_ENERGY = 0xffe664;
    private static final int COLOR_LABEL = 0xa0a0a8;
    private static final int COLOR_LABELREF = 0xa0a0a8;
    private static final int COLOR_REGISTER = 0x506080;
    private static final int COLOR_DEAD = 0x555555;

    // Organism colors as int RGB (avoids Color.getRGB() conversion per frame)
    private static final int[] ORGANISM_PALETTE = {
        0x32cd32, 0x1e90ff, 0xdc143c, 0xffd700, 0xffa500, 0x9370db, 0x00ffff
    };

    // Marker size for organism rendering (IP triangles, DP squares)
    private static final int ORGANISM_MARKER_SIZE = 4;

    // Dimensions (initialized in init())
    private int imageWidth;
    private int imageHeight;
    private int worldWidth;
    private int worldHeight;

    // Frame buffer
    private BufferedImage frame;
    private int[] frameBuffer;

    // Persistent cell state
    private int[] cellColors;

    // Lazy organism access for sampling mode
    private TickData lastSnapshot;
    private TickDelta lastDelta;

    // Genome hash → palette color (insertion-order assignment)
    private final Map<Long, Integer> genomeHashColorMap = new LinkedHashMap<>();

    // Previous organism positions for cleanup during incremental rendering
    private List<OrganismPosition> previousOrganismPositions = new ArrayList<>();

    private boolean initialized = false;

    /**
     * Tracks organism marker positions for cleanup between frames.
     */
    private static class OrganismPosition {
        final int x;
        final int y;

        OrganismPosition(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Default constructor for PicoCLI instantiation.
     */
    public ExactFrameRenderer() {
        // Options populated by PicoCLI
    }

    @Override
    public void init(EnvironmentProperties envProps) {
        if (scale < 1) {
            throw new IllegalArgumentException("Exact renderer scale must be >= 1, got: " + scale);
        }

        super.init(envProps);
        this.worldWidth = envProps.getWorldShape()[0];
        this.worldHeight = envProps.getWorldShape()[1];
        this.imageWidth = worldWidth * scale;
        this.imageHeight = worldHeight * scale;

        this.frame = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_RGB);
        this.frameBuffer = ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();

        // Persistent cell color state
        int worldSize = worldWidth * worldHeight;
        this.cellColors = new int[worldSize];
        Arrays.fill(cellColors, COLOR_EMPTY);

        this.lastSnapshot = null;
        this.lastDelta = null;
        this.initialized = true;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Core rendering API
    // ─────────────────────────────────────────────────────────────────────────────

    @Override
    protected int[] doRenderSnapshot(TickData snapshot) {
        ensureInitialized();

        // Reset and draw all cells
        Arrays.fill(cellColors, COLOR_EMPTY);
        Arrays.fill(frameBuffer, COLOR_EMPTY);
        previousOrganismPositions.clear();

        CellDataColumns columns = snapshot.getCellColumns();
        int cellCount = columns.getFlatIndicesCount();
        for (int i = 0; i < cellCount; i++) {
            int flatIndex = columns.getFlatIndices(i);
            int color = getCellColor(columns.getMoleculeData(i));
            cellColors[flatIndex] = color;
            drawCellFromFlatIndex(flatIndex, color);
        }

        this.lastSnapshot = snapshot;
        this.lastDelta = null;

        renderOrganismsAndTrack(snapshot.getOrganismsList());
        return frameBuffer;
    }

    @Override
    protected int[] doRenderDelta(TickDelta delta) {
        ensureInitialized();

        // Clear previous organism positions first
        for (OrganismPosition pos : previousOrganismPositions) {
            clearOrganismArea(pos);
        }
        previousOrganismPositions.clear();

        // Only update changed cells (incremental!)
        CellDataColumns changed = delta.getChangedCells();
        int changedCount = changed.getFlatIndicesCount();
        for (int i = 0; i < changedCount; i++) {
            int flatIndex = changed.getFlatIndices(i);
            int color = getCellColor(changed.getMoleculeData(i));
            cellColors[flatIndex] = color;
            drawCellFromFlatIndex(flatIndex, color);
        }

        this.lastDelta = delta;

        renderOrganismsAndTrack(delta.getOrganismsList());
        return frameBuffer;
    }

    @Override
    public void applySnapshotState(TickData snapshot) {
        ensureInitialized();

        // Update state only (no drawing) - for sampling mode
        Arrays.fill(cellColors, COLOR_EMPTY);

        CellDataColumns columns = snapshot.getCellColumns();
        int cellCount = columns.getFlatIndicesCount();
        for (int i = 0; i < cellCount; i++) {
            int flatIndex = columns.getFlatIndices(i);
            cellColors[flatIndex] = getCellColor(columns.getMoleculeData(i));
        }

        this.lastSnapshot = snapshot;
        this.lastDelta = null;
    }

    @Override
    public void applyDeltaState(TickDelta delta) {
        ensureInitialized();

        // Update state only (no drawing) - for sampling mode
        CellDataColumns changed = delta.getChangedCells();
        int changedCount = changed.getFlatIndicesCount();
        for (int i = 0; i < changedCount; i++) {
            int flatIndex = changed.getFlatIndices(i);
            cellColors[flatIndex] = getCellColor(changed.getMoleculeData(i));
        }

        this.lastDelta = delta;
    }

    @Override
    public int[] renderCurrentState() {
        ensureInitialized();

        // Full redraw from state (for sampling mode)
        renderAllCells();
        renderOrganisms(getOrganismsLazy());

        return frameBuffer;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Cell rendering
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Renders all cells from the persistent cellColors state.
     */
    private void renderAllCells() {
        for (int wy = 0; wy < worldHeight; wy++) {
            for (int wx = 0; wx < worldWidth; wx++) {
                int flatIndex = wx * worldHeight + wy;  // Row-major
                drawCell(wx, wy, cellColors[flatIndex]);
            }
        }
    }

    private void drawCellFromFlatIndex(int flatIndex, int color) {
        // Row-major: flatIndex = x * height + y
        int cellX = flatIndex / worldHeight;
        int cellY = flatIndex % worldHeight;
        drawCell(cellX, cellY, color);
    }

    private void drawCell(int cellX, int cellY, int color) {
        int startX = cellX * scale;
        int startY = cellY * scale;
        for (int y = 0; y < scale; y++) {
            int rowStart = (startY + y) * imageWidth + startX;
            Arrays.fill(frameBuffer, rowStart, rowStart + scale, color);
        }
    }

    private int getCellColor(int moleculeInt) {
        if (moleculeInt == 0) return COLOR_EMPTY;
        int moleculeType = moleculeInt & Config.TYPE_MASK;
        if (moleculeType == Config.TYPE_CODE) return COLOR_CODE;
        if (moleculeType == Config.TYPE_DATA) return COLOR_DATA;
        if (moleculeType == Config.TYPE_ENERGY) return COLOR_ENERGY;
        if (moleculeType == Config.TYPE_STRUCTURE) return COLOR_STRUCTURE;
        if (moleculeType == Config.TYPE_LABEL) return COLOR_LABEL;
        if (moleculeType == Config.TYPE_LABELREF) return COLOR_LABELREF;
        if (moleculeType == Config.TYPE_REGISTER) return COLOR_REGISTER;
        return COLOR_EMPTY;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Organism rendering
    // ─────────────────────────────────────────────────────────────────────────────

    private List<OrganismState> getOrganismsLazy() {
        if (lastDelta != null) {
            return lastDelta.getOrganismsList();
        } else if (lastSnapshot != null) {
            return lastSnapshot.getOrganismsList();
        }
        return List.of();
    }

    /**
     * Renders organisms without tracking (for sampling mode full redraws).
     */
    private void renderOrganisms(List<OrganismState> organisms) {
        renderOrganismsInternal(organisms, false);
    }

    /**
     * Renders organisms and tracks positions for cleanup during incremental rendering.
     */
    private void renderOrganismsAndTrack(List<OrganismState> organisms) {
        renderOrganismsInternal(organisms, true);
    }

    /**
     * Internal organism rendering with optional position tracking.
     *
     * @param organisms List of organisms to render.
     * @param trackPositions If true, stores positions for later cleanup.
     */
    private void renderOrganismsInternal(List<OrganismState> organisms, boolean trackPositions) {
        for (OrganismState org : organisms) {
            int ipX = org.getIp().getComponents(0);
            int ipY = org.getIp().getComponents(1);

            if (trackPositions) {
                previousOrganismPositions.add(new OrganismPosition(ipX, ipY));
            }

            if (org.getIsDead()) {
                drawSquareMarker(ipX, ipY, COLOR_DEAD, ORGANISM_MARKER_SIZE);
            } else {
                int color = getGenomeHashColor(org.getGenomeHash());

                for (Vector dp : org.getDataPointersList()) {
                    int dpX = dp.getComponents(0);
                    int dpY = dp.getComponents(1);
                    if (trackPositions) {
                        previousOrganismPositions.add(new OrganismPosition(dpX, dpY));
                    }
                    drawSquareMarker(dpX, dpY, color, ORGANISM_MARKER_SIZE);
                }

                int dvX = org.getDv().getComponents(0);
                int dvY = org.getDv().getComponents(1);
                drawTriangle(ipX, ipY, color, ORGANISM_MARKER_SIZE, dvX, dvY);
            }
        }
    }

    /**
     * Clears the organism marker area by redrawing the underlying cells.
     */
    private void clearOrganismArea(OrganismPosition pos) {
        int half = ORGANISM_MARKER_SIZE / 2;

        for (int dy = -half; dy <= half; dy++) {
            for (int dx = -half; dx <= half; dx++) {
                int cellX = pos.x + dx;
                int cellY = pos.y + dy;

                if (cellX < 0 || cellX >= worldWidth || cellY < 0 || cellY >= worldHeight) {
                    continue;
                }

                int flatIndex = cellX * worldHeight + cellY;
                int color = cellColors[flatIndex];
                drawCell(cellX, cellY, color);
            }
        }
    }

    /**
     * Returns the palette color for a genome hash, assigning colors in insertion order.
     *
     * @param genomeHash The genome hash of the organism.
     * @return RGB color from the organism palette.
     */
    private int getGenomeHashColor(long genomeHash) {
        if (genomeHash == 0) return ORGANISM_PALETTE[0];
        return ORGANISM_PALETTE[genomeHashColorMap
                .computeIfAbsent(genomeHash, k -> genomeHashColorMap.size() % ORGANISM_PALETTE.length)];
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Shape drawing
    // ─────────────────────────────────────────────────────────────────────────────

    private void drawSquareMarker(int cellX, int cellY, int color, int sizeInCells) {
        int half = sizeInCells / 2;
        int startX = (cellX - half) * scale;
        int startY = (cellY - half) * scale;
        int pixelSize = sizeInCells * scale;

        for (int y = 0; y < pixelSize; y++) {
            int py = startY + y;
            if (py < 0 || py >= imageHeight) continue;

            int rowStart = py * imageWidth;
            int lineStart = Math.max(0, startX);
            int lineEnd = Math.min(imageWidth, startX + pixelSize);

            if (lineStart < lineEnd) {
                Arrays.fill(frameBuffer, rowStart + lineStart, rowStart + lineEnd, color);
            }
        }
    }

    private void drawTriangle(int cellX, int cellY, int color, int sizeInCells, int dvX, int dvY) {
        int centerX = cellX * scale + scale / 2;
        int centerY = cellY * scale + scale / 2;
        int halfSize = (sizeInCells * scale) / 2;

        // If no direction, draw circle instead
        if (dvX == 0 && dvY == 0) {
            drawCircle(centerX, centerY, halfSize, color);
            return;
        }

        double length = Math.sqrt(dvX * dvX + dvY * dvY);
        double dirX = dvX / length;
        double dirY = dvY / length;

        int tipX = (int) (centerX + dirX * halfSize);
        int tipY = (int) (centerY + dirY * halfSize);
        int base1X = (int) (centerX - dirX * halfSize - dirY * halfSize);
        int base1Y = (int) (centerY - dirY * halfSize + dirX * halfSize);
        int base2X = (int) (centerX - dirX * halfSize + dirY * halfSize);
        int base2Y = (int) (centerY - dirY * halfSize - dirX * halfSize);

        fillTriangle(tipX, tipY, base1X, base1Y, base2X, base2Y, color);
    }

    private void drawCircle(int centerX, int centerY, int radius, int color) {
        int startX = Math.max(0, centerX - radius);
        int startY = Math.max(0, centerY - radius);
        int endX = Math.min(imageWidth - 1, centerX + radius);
        int endY = Math.min(imageHeight - 1, centerY + radius);
        int radiusSq = radius * radius;

        for (int y = startY; y <= endY; y++) {
            for (int x = startX; x <= endX; x++) {
                int dx = x - centerX;
                int dy = y - centerY;
                if (dx * dx + dy * dy <= radiusSq) {
                    frameBuffer[y * imageWidth + x] = color;
                }
            }
        }
    }

    private void fillTriangle(int x1, int y1, int x2, int y2, int x3, int y3, int color) {
        // Sort vertices by Y coordinate (bubble sort, no allocation)
        if (y1 > y2) { int t = x1; x1 = x2; x2 = t; t = y1; y1 = y2; y2 = t; }
        if (y2 > y3) { int t = x2; x2 = x3; x3 = t; t = y2; y2 = y3; y3 = t; }
        if (y1 > y2) { int t = x1; x1 = x2; x2 = t; t = y1; y1 = y2; y2 = t; }

        int minY = Math.max(0, y1);
        int maxY = Math.min(imageHeight - 1, y3);

        // Scan-line fill without per-line allocation
        for (int y = minY; y <= maxY; y++) {
            int xLeft, xRight;

            if (y < y2) {
                // Upper part: edges y1→y2 and y1→y3
                xLeft = (y2 != y1) ? x1 + (x2 - x1) * (y - y1) / (y2 - y1) : x1;
                xRight = (y3 != y1) ? x1 + (x3 - x1) * (y - y1) / (y3 - y1) : x1;
            } else {
                // Lower part: edges y2→y3 and y1→y3
                xLeft = (y3 != y2) ? x2 + (x3 - x2) * (y - y2) / (y3 - y2) : x2;
                xRight = (y3 != y1) ? x1 + (x3 - x1) * (y - y1) / (y3 - y1) : x1;
            }

            if (xLeft > xRight) { int t = xLeft; xLeft = xRight; xRight = t; }

            int lineStart = Math.max(0, xLeft);
            int lineEnd = Math.min(imageWidth - 1, xRight);
            if (lineStart <= lineEnd) {
                Arrays.fill(frameBuffer, y * imageWidth + lineStart, y * imageWidth + lineEnd + 1, color);
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
        return imageWidth;
    }

    @Override
    public int getImageHeight() {
        ensureInitialized();
        return imageHeight;
    }
}
