package org.evochora.cli.rendering;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.EnvironmentProperties;

/**
 * Renders simulation ticks to an image buffer.
 * <p>
 * This class uses the same color palette as the web visualizer to produce
 * visually consistent output. It is optimized for performance by drawing
 * directly to the pixel buffer of a BufferedImage.
 * <p>
 * <strong>Incremental Rendering:</strong> For video rendering with delta compression,
 * use {@link #renderSnapshot(TickData)} for the first frame and {@link #renderDelta(TickDelta)}
 * for subsequent frames. This avoids redrawing unchanged cells, providing massive
 * performance improvements (only ~100-500 changed cells per frame instead of all cells).
 * <p>
 * <strong>Thread Safety:</strong> Not thread-safe. Use one renderer per thread.
 */
public class SimulationRenderer {

    private final EnvironmentProperties envProps;
    private final int cellSize;
    private final int imageWidth;
    private final int imageHeight;
    private final int worldWidth;
    private final int worldHeight;
    private final BufferedImage frame;
    private final int[] frameBuffer;
    private final int[] strides;

    // Internal cell state for incremental rendering
    private final int[] cellColors;

    // Previous organism positions for cleanup during incremental rendering
    private List<OrganismPosition> previousOrganismPositions = new ArrayList<>();

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
     * Creates a new renderer for a simulation run.
     *
     * @param envProps Environment properties (world shape, topology).
     * @param cellSize The size of each cell in pixels.
     */
    public SimulationRenderer(EnvironmentProperties envProps, int cellSize) {
        this.envProps = envProps;
        this.cellSize = cellSize;
        this.worldWidth = envProps.getWorldShape()[0];
        this.worldHeight = envProps.getWorldShape()[1];
        this.imageWidth = worldWidth * cellSize;
        this.imageHeight = worldHeight * cellSize;

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
    }

    private int coordinatesToFlatIndex(int[] coords) {
        int flatIndex = 0;
        for (int i = 0; i < coords.length; i++) {
            flatIndex += coords[i] * strides[i];
        }
        return flatIndex;
    }

    /**
     * Renders a single tick into an array of pixel data.
     *
     * @param tick The tick data to render.
     * @return An array of integers representing the RGB pixel data of the rendered frame.
     */
    public int[] render(TickData tick) {
        Arrays.fill(frameBuffer, COLOR_EMPTY_BG);

        CellDataColumns columns = tick.getCellColumns();
        int cellCount = columns.getFlatIndicesCount();

        for (int i = 0; i < cellCount; i++) {
            int flatIndex = columns.getFlatIndices(i);
            int moleculeInt = columns.getMoleculeData(i);
            int[] coord = envProps.flatIndexToCoordinates(flatIndex);
            int color = getCellColor(moleculeInt);
            drawCell(coord[0], coord[1], color);
        }

        drawOrganisms(tick.getOrganismsList());
        return frameBuffer;
    }

    /**
     * Renders a snapshot tick, initializing the internal cell state.
     *
     * @param snapshot The snapshot tick data to render.
     * @return An array of integers representing the RGB pixel data of the rendered frame.
     */
    public int[] renderSnapshot(TickData snapshot) {
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
            int[] coord = envProps.flatIndexToCoordinates(flatIndex);
            drawCell(coord[0], coord[1], color);
        }

        drawOrganismsAndTrack(snapshot.getOrganismsList());
        return frameBuffer;
    }

    /**
     * Renders a delta incrementally, only updating changed cells.
     *
     * @param delta The delta containing only changed cells.
     * @return An array of integers representing the RGB pixel data of the rendered frame.
     */
    public int[] renderDelta(TickDelta delta) {
        // Clear previous organism positions
        for (OrganismPosition pos : previousOrganismPositions) {
            int color = cellColors[pos.flatIndex];
            int[] coord = envProps.flatIndexToCoordinates(pos.flatIndex);
            drawCell(coord[0], coord[1], color);
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
            int[] coord = envProps.flatIndexToCoordinates(flatIndex);
            drawCell(coord[0], coord[1], color);
        }

        drawOrganismsAndTrack(delta.getOrganismsList());
        return frameBuffer;
    }

    private void drawOrganisms(List<OrganismState> organisms) {
        for (OrganismState org : organisms) {
            if (org.getIsDead()) {
                drawLargeMarker(org.getIp().getComponents(0), org.getIp().getComponents(1), COLOR_DEAD, 4);
            } else {
                Color orgColor = getOrganismColor(org.getOrganismId());
                int opaqueColor = orgColor.getRGB() | 0xFF000000;

                for (Vector dp : org.getDataPointersList()) {
                    drawLargeMarker(dp.getComponents(0), dp.getComponents(1), opaqueColor, 4);
                }

                int[] dv = new int[]{org.getDv().getComponents(0), org.getDv().getComponents(1)};
                drawTriangle(org.getIp().getComponents(0), org.getIp().getComponents(1), opaqueColor, 4, dv);
            }
        }
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
        int startX = cellX * cellSize;
        int startY = cellY * cellSize;
        for (int y = 0; y < cellSize; y++) {
            int startIndex = (startY + y) * imageWidth + startX;
            Arrays.fill(frameBuffer, startIndex, startIndex + cellSize, color);
        }
    }

    private void drawLargeMarker(int cellX, int cellY, int color, int sizeInCells) {
        int offset = sizeInCells / 2;
        int startX = (cellX - offset) * cellSize;
        int startY = (cellY - offset) * cellSize;
        int markerSizePixels = sizeInCells * cellSize;

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
        int centerX = cellX * cellSize + (cellSize / 2);
        int centerY = cellY * cellSize + (cellSize / 2);
        int halfSize = (sizeInCells * cellSize) / 2;

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
                int startX = Math.max(0, xCoords[0]);
                int endX = Math.min(imageWidth - 1, xCoords[count - 1]);

                if (startX <= endX) {
                    Arrays.fill(frameBuffer, y * imageWidth + startX, y * imageWidth + endX + 1, color);
                }
            }
        }
    }

    /**
     * Returns the underlying BufferedImage for overlay rendering.
     *
     * @return The frame BufferedImage.
     */
    public BufferedImage getFrame() {
        return frame;
    }

    /**
     * Returns the image width in pixels.
     *
     * @return Image width.
     */
    public int getImageWidth() {
        return imageWidth;
    }

    /**
     * Returns the image height in pixels.
     *
     * @return Image height.
     */
    public int getImageHeight() {
        return imageHeight;
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
