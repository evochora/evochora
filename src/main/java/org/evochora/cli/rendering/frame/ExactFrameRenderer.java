package org.evochora.cli.rendering.frame;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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

    // Initialized after CLI parsing via init()
    private int imageWidth;
    private int imageHeight;
    private int worldWidth;
    private int worldHeight;
    private BufferedImage frame;
    private int[] frameBuffer;
    private int[] strides;
    private int[] cellColors;
    private int[] coordBuffer;  // Reusable buffer for coordinate conversion
    private List<OrganismPosition> previousOrganismPositions;
    private boolean initialized = false;

    // Colors with full alpha (0xFF) for ARGB format
    private static final int COLOR_EMPTY_BG = 0xFF000000;
    private static final int COLOR_CODE_BG = 0xFF3c5078;
    private static final int COLOR_DATA_BG = 0xFF32323c;
    private static final int COLOR_STRUCTURE_BG = 0xFFff7878;
    private static final int COLOR_ENERGY_BG = 0xFFffe664;
    private static final int COLOR_LABEL_BG = 0xFFa0a0a8;
    private static final int COLOR_DEAD = 0xFF555555;

    private static final Color[] ORGANISM_COLOR_PALETTE = {
        Color.decode("#32cd32"), Color.decode("#1e90ff"), Color.decode("#dc143c"),
        Color.decode("#ffd700"), Color.decode("#ffa500"), Color.decode("#9370db"),
        Color.decode("#00ffff")
    };

    private final Map<Integer, Color> organismColorMap = new HashMap<>();

    /**
     * Default constructor for PicoCLI instantiation.
     */
    public ExactFrameRenderer() {
        // Options populated by PicoCLI
    }

    /**
     * Initializes the renderer with environment properties.
     * <p>
     * Must be called after PicoCLI has parsed options and before rendering.
     *
     * @param envProps Environment properties (world shape, topology).
     * @throws IllegalArgumentException if scale is less than 1.
     */
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

        // Calculate strides for coordinate to flatIndex conversion
        int[] shape = envProps.getWorldShape();
        this.strides = new int[shape.length];
        int stride = 1;
        for (int i = shape.length - 1; i >= 0; i--) {
            strides[i] = stride;
            stride *= shape[i];
        }

        // Initialize cell colors array
        this.cellColors = new int[stride];
        Arrays.fill(cellColors, COLOR_EMPTY_BG);

        // Reusable buffer for coordinate conversion (avoids allocation per cell)
        this.coordBuffer = new int[shape.length];

        this.previousOrganismPositions = new ArrayList<>();
        this.initialized = true;
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("Renderer not initialized. Call init(EnvironmentProperties) first.");
        }
    }

    private int coordinatesToFlatIndex(int[] coords) {
        int flatIndex = 0;
        for (int i = 0; i < coords.length; i++) {
            flatIndex += coords[i] * strides[i];
        }
        return flatIndex;
    }

    @Override
    protected int[] doRenderSnapshot(TickData snapshot) {
        ensureInitialized();

        Arrays.fill(cellColors, COLOR_EMPTY_BG);
        Arrays.fill(frameBuffer, COLOR_EMPTY_BG);
        previousOrganismPositions.clear();

        CellDataColumns columns = snapshot.getCellColumns();
        int cellCount = columns.getFlatIndicesCount();

        for (int i = 0; i < cellCount; i++) {
            int flatIndex = columns.getFlatIndices(i);
            int moleculeInt = columns.getMoleculeData(i);
            int color = getCellColor(moleculeInt);
            cellColors[flatIndex] = color;
            envProps.flatIndexToCoordinates(flatIndex, coordBuffer);
            drawCell(coordBuffer[0], coordBuffer[1], color);
        }

        drawOrganismsAndTrack(snapshot.getOrganismsList());
        return frameBuffer;
    }

    @Override
    protected int[] doRenderDelta(TickDelta delta) {
        ensureInitialized();

        // Clear previous organism positions
        for (OrganismPosition pos : previousOrganismPositions) {
            int color = cellColors[pos.flatIndex];
            envProps.flatIndexToCoordinates(pos.flatIndex, coordBuffer);
            drawCell(coordBuffer[0], coordBuffer[1], color);
            clearOrganismArea(pos);
        }
        previousOrganismPositions.clear();

        // Apply changed cells
        CellDataColumns changed = delta.getChangedCells();
        int changedCount = changed.getFlatIndicesCount();

        for (int i = 0; i < changedCount; i++) {
            int flatIndex = changed.getFlatIndices(i);
            int moleculeInt = changed.getMoleculeData(i);
            int color = getCellColor(moleculeInt);
            cellColors[flatIndex] = color;
            envProps.flatIndexToCoordinates(flatIndex, coordBuffer);
            drawCell(coordBuffer[0], coordBuffer[1], color);
        }

        drawOrganismsAndTrack(delta.getOrganismsList());
        return frameBuffer;
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

    private void drawOrganismsAndTrack(List<OrganismState> organisms) {
        for (OrganismState org : organisms) {
            int ipX = org.getIp().getComponents(0);
            int ipY = org.getIp().getComponents(1);
            int ipFlatIndex = coordinatesToFlatIndex(new int[]{ipX, ipY});
            previousOrganismPositions.add(new OrganismPosition(ipFlatIndex, ipX, ipY));

            if (org.getIsDead()) {
                drawLargeMarker(ipX, ipY, COLOR_DEAD, 4);
            } else {
                Color orgColor = getOrganismColor(org.getOrganismId());
                int opaqueColor = orgColor.getRGB() | 0xFF000000;

                for (Vector dp : org.getDataPointersList()) {
                    int dpX = dp.getComponents(0);
                    int dpY = dp.getComponents(1);
                    int dpFlatIndex = coordinatesToFlatIndex(new int[]{dpX, dpY});
                    previousOrganismPositions.add(new OrganismPosition(dpFlatIndex, dpX, dpY));
                    drawLargeMarker(dpX, dpY, opaqueColor, 4);
                }

                int[] dv = new int[]{org.getDv().getComponents(0), org.getDv().getComponents(1)};
                drawTriangle(ipX, ipY, opaqueColor, 4, dv);
            }
        }
    }

    private void clearOrganismArea(OrganismPosition pos) {
        int markerSize = 4;
        int offset = markerSize / 2;

        for (int dy = -offset; dy <= offset; dy++) {
            for (int dx = -offset; dx <= offset; dx++) {
                int cellX = pos.x + dx;
                int cellY = pos.y + dy;

                if (cellX < 0 || cellX >= worldWidth || cellY < 0 || cellY >= worldHeight) {
                    continue;
                }

                int flatIndex = coordinatesToFlatIndex(new int[]{cellX, cellY});
                int color = cellColors[flatIndex];
                drawCell(cellX, cellY, color);
            }
        }
    }

    private void drawCell(int cellX, int cellY, int color) {
        int startX = cellX * scale;
        int startY = cellY * scale;
        for (int y = 0; y < scale; y++) {
            int startIndex = (startY + y) * imageWidth + startX;
            Arrays.fill(frameBuffer, startIndex, startIndex + scale, color);
        }
    }

    private void drawLargeMarker(int cellX, int cellY, int color, int sizeInCells) {
        int offset = sizeInCells / 2;
        int startX = (cellX - offset) * scale;
        int startY = (cellY - offset) * scale;
        int markerSizePixels = sizeInCells * scale;

        for (int y = 0; y < markerSizePixels; y++) {
            int pixelY = startY + y;
            if (pixelY < 0 || pixelY >= imageHeight) continue;

            int startIndex = pixelY * imageWidth + startX;
            int endIndex = startIndex + markerSizePixels;

            if (startX < 0) {
                startIndex = pixelY * imageWidth;
                endIndex = Math.min(endIndex, pixelY * imageWidth + imageWidth);
            } else if (endIndex > (pixelY + 1) * imageWidth) {
                endIndex = (pixelY + 1) * imageWidth;
            }

            if (startIndex < endIndex && startIndex >= 0 && endIndex <= frameBuffer.length) {
                Arrays.fill(frameBuffer, startIndex, endIndex, color);
            }
        }
    }

    private int getCellColor(int moleculeInt) {
        int moleculeType = moleculeInt & Config.TYPE_MASK;

        if (moleculeType == Config.TYPE_CODE) {
            return COLOR_CODE_BG;
        } else if (moleculeType == Config.TYPE_DATA) {
            return COLOR_DATA_BG;
        } else if (moleculeType == Config.TYPE_ENERGY) {
            return COLOR_ENERGY_BG;
        } else if (moleculeType == Config.TYPE_STRUCTURE) {
            return COLOR_STRUCTURE_BG;
        } else if (moleculeType == Config.TYPE_LABEL) {
            return COLOR_LABEL_BG;
        } else {
            return COLOR_EMPTY_BG;
        }
    }

    private Color getOrganismColor(int organismId) {
        return organismColorMap.computeIfAbsent(organismId, id -> {
            int paletteIndex = (id - 1) % ORGANISM_COLOR_PALETTE.length;
            if (paletteIndex < 0) paletteIndex = 0;
            return ORGANISM_COLOR_PALETTE[paletteIndex];
        });
    }

    private void drawTriangle(int cellX, int cellY, int color, int sizeInCells, int[] dv) {
        int centerX = cellX * scale + (scale / 2);
        int centerY = cellY * scale + (scale / 2);
        int halfSize = (sizeInCells * scale) / 2;

        if (dv != null && dv.length >= 2 && (dv[0] != 0 || dv[1] != 0)) {
            double length = Math.sqrt(dv[0] * dv[0] + dv[1] * dv[1]);
            if (length > 0) {
                double dirX = dv[0] / length;
                double dirY = dv[1] / length;

                int tipX = (int)(centerX + dirX * halfSize);
                int tipY = (int)(centerY + dirY * halfSize);
                int base1X = (int)(centerX - dirX * halfSize + (-dirY) * halfSize);
                int base1Y = (int)(centerY - dirY * halfSize + dirX * halfSize);
                int base2X = (int)(centerX - dirX * halfSize - (-dirY) * halfSize);
                int base2Y = (int)(centerY - dirY * halfSize - dirX * halfSize);

                drawFilledTriangle(tipX, tipY, base1X, base1Y, base2X, base2Y, color);
            } else {
                drawCircle(centerX, centerY, halfSize, color);
            }
        } else {
            drawCircle(centerX, centerY, halfSize, color);
        }
    }

    private void drawCircle(int centerX, int centerY, int radius, int color) {
        int startX = Math.max(0, centerX - radius);
        int startY = Math.max(0, centerY - radius);
        int endX = Math.min(imageWidth - 1, centerX + radius);
        int endY = Math.min(imageHeight - 1, centerY + radius);

        int radiusSquared = radius * radius;
        for (int y = startY; y <= endY; y++) {
            for (int x = startX; x <= endX; x++) {
                int dx = x - centerX;
                int dy = y - centerY;
                if (dx * dx + dy * dy <= radiusSquared) {
                    frameBuffer[y * imageWidth + x] = color;
                }
            }
        }
    }

    private void drawFilledTriangle(int x1, int y1, int x2, int y2, int x3, int y3, int color) {
        int[] xs = {x1, x2, x3};
        int[] ys = {y1, y2, y3};

        if (ys[0] > ys[1]) { int t = xs[0]; xs[0] = xs[1]; xs[1] = t; t = ys[0]; ys[0] = ys[1]; ys[1] = t; }
        if (ys[1] > ys[2]) { int t = xs[1]; xs[1] = xs[2]; xs[2] = t; t = ys[1]; ys[1] = ys[2]; ys[2] = t; }
        if (ys[0] > ys[1]) { int t = xs[0]; xs[0] = xs[1]; xs[1] = t; t = ys[0]; ys[0] = ys[1]; ys[1] = t; }

        int v1x = xs[0], v1y = ys[0];
        int v2x = xs[1], v2y = ys[1];
        int v3x = xs[2], v3y = ys[2];

        int minY = Math.max(0, v1y);
        int maxY = Math.min(imageHeight - 1, v3y);

        for (int y = minY; y <= maxY; y++) {
            int[] xCoords = new int[3];
            int count = 0;

            if (v1y != v2y && y >= Math.min(v1y, v2y) && y <= Math.max(v1y, v2y)) {
                xCoords[count++] = v1x + (v2x - v1x) * (y - v1y) / (v2y - v1y);
            }
            if (v2y != v3y && y >= Math.min(v2y, v3y) && y <= Math.max(v2y, v3y)) {
                xCoords[count++] = v2x + (v3x - v2x) * (y - v2y) / (v3y - v2y);
            }
            if (v1y != v3y && y >= Math.min(v1y, v3y) && y <= Math.max(v1y, v3y)) {
                xCoords[count++] = v1x + (v3x - v1x) * (y - v1y) / (v3y - v1y);
            }

            if (count >= 2) {
                Arrays.sort(xCoords, 0, count);
                int lineStartX = Math.max(0, xCoords[0]);
                int lineEndX = Math.min(imageWidth - 1, xCoords[count - 1]);

                if (lineStartX <= lineEndX) {
                    Arrays.fill(frameBuffer, y * imageWidth + lineStartX, y * imageWidth + lineEndX + 1, color);
                }
            }
        }
    }

    private static class OrganismPosition {
        final int flatIndex;
        final int x;
        final int y;

        OrganismPosition(int flatIndex, int x, int y) {
            this.flatIndex = flatIndex;
            this.x = x;
            this.y = y;
        }
    }
}
