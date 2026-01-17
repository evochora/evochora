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
    private final int[] strides;  // For coordinate to flatIndex conversion

    // Internal cell state for incremental rendering
    private final int[] cellColors;  // Color per cell (flatIndex -> RGB color)
    
    // Previous organism positions for cleanup during incremental rendering
    private List<OrganismPosition> previousOrganismPositions = new ArrayList<>();

    private final int colorEmptyBg = Color.decode("#000000").getRGB(); // Pure black background
    private final int colorCodeBg = Color.decode("#3c5078").getRGB();
    private final int colorDataBg = Color.decode("#32323c").getRGB();
    private final int colorStructureBg = Color.decode("#ff7878").getRGB();
    private final int colorEnergyBg = Color.decode("#ffe664").getRGB();
    private final int colorDead = Color.decode("#505050").getRGB();
    
    private final Color[] organismColorPalette = {
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
        int totalCells = stride; // stride now equals total cells
        this.cellColors = new int[totalCells];
        Arrays.fill(cellColors, colorEmptyBg);
    }

    /**
     * Converts coordinates to a flat index.
     * This is the inverse of {@link EnvironmentProperties#flatIndexToCoordinates(int)}.
     *
     * @param coords The coordinates to convert
     * @return The flat index
     */
    private int coordinatesToFlatIndex(int[] coords) {
        int flatIndex = 0;
        for (int i = 0; i < coords.length; i++) {
            flatIndex += coords[i] * strides[i];
        }
        return flatIndex;
    }

    /**
     * Renders a single tick into an array of pixel data.
     * <p>
     * This is the legacy method that redraws everything from scratch.
     * For video rendering with delta compression, prefer using
     * {@link #renderSnapshot(TickData)} and {@link #renderDelta(TickDelta)}.
     *
     * @param tick The tick data to render.
     * @return An array of integers representing the RGB pixel data of the rendered frame.
     */
    public int[] render(TickData tick) {
        // 1. Draw background - use direct array fill instead of Graphics2D for performance
        Arrays.fill(frameBuffer, colorEmptyBg);

        // 2. Draw cells
        // Use EnvironmentProperties.flatIndexToCoordinates() for correct conversion
        // This handles strides correctly for any dimensionality (2D, 3D, etc.)
        CellDataColumns columns = tick.getCellColumns();
        int cellCount = columns.getFlatIndicesCount();
        
        for (int i = 0; i < cellCount; i++) {
            int flatIndex = columns.getFlatIndices(i);
            int moleculeInt = columns.getMoleculeData(i);
            
            int[] coord = envProps.flatIndexToCoordinates(flatIndex);
            
            // For 2D rendering, use first two coordinates
            // Note: This assumes 2D world for video rendering (which is standard)
            int x = coord[0];
            int y = coord[1];
            
            int color = getCellColor(moleculeInt);
            drawCell(x, y, color);
        }

        // 3. Draw organisms
        for (OrganismState org : tick.getOrganismsList()) {
            if (org.getIsDead()) {
                // Dead organisms: draw IP as 4x larger marker
                drawLargeMarker(org.getIp().getComponents(0), org.getIp().getComponents(1), colorDead, 4);
            } else {
                Color orgColor = getOrganismColor(org.getOrganismId());

                // Draw Data Pointers (DPs) as squares in organism color, 4x larger
                for (Vector dp : org.getDataPointersList()) {
                    drawLargeMarker(dp.getComponents(0), dp.getComponents(1), orgColor.getRGB(), 4);
                }

                // Draw Instruction Pointer (IP) as triangle/arrow pointing in DV direction
                int[] dv = new int[]{
                    org.getDv().getComponents(0),
                    org.getDv().getComponents(1)
                };
                drawTriangle(org.getIp().getComponents(0), org.getIp().getComponents(1), 
                            orgColor.getRGB(), 4, dv);
            }
        }
        
        return frameBuffer;
    }

    /**
     * Renders a snapshot tick, initializing the internal cell state.
     * <p>
     * Use this method for the first frame in a chunk (the snapshot).
     * Subsequent frames should use {@link #renderDelta(TickDelta)}.
     *
     * @param snapshot The snapshot tick data to render.
     * @return An array of integers representing the RGB pixel data of the rendered frame.
     */
    public int[] renderSnapshot(TickData snapshot) {
        // 1. Reset internal state
        Arrays.fill(cellColors, colorEmptyBg);
        Arrays.fill(frameBuffer, colorEmptyBg);
        previousOrganismPositions.clear();
        
        // 2. Draw and track all cells
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
        
        // 3. Draw organisms and track positions
        drawOrganismsAndTrack(snapshot.getOrganismsList());
        
        return frameBuffer;
    }

    /**
     * Renders a delta incrementally, only updating changed cells.
     * <p>
     * This method provides massive performance improvements for video rendering
     * by only redrawing cells that have changed since the last frame.
     * <p>
     * <strong>Prerequisites:</strong> Must be called after {@link #renderSnapshot(TickData)}
     * or another {@link #renderDelta(TickDelta)} call. The internal state must be initialized.
     *
     * @param delta The delta containing only changed cells.
     * @return An array of integers representing the RGB pixel data of the rendered frame.
     */
    public int[] renderDelta(TickDelta delta) {
        // 1. Clear previous organism positions (restore cell colors)
        for (OrganismPosition pos : previousOrganismPositions) {
            int color = cellColors[pos.flatIndex];
            int[] coord = envProps.flatIndexToCoordinates(pos.flatIndex);
            drawCell(coord[0], coord[1], color);
            
            // Also clear the larger marker area around organisms
            clearOrganismArea(pos);
        }
        previousOrganismPositions.clear();
        
        // 2. Apply only changed cells from delta
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
        
        // 3. Draw organisms and track positions
        drawOrganismsAndTrack(delta.getOrganismsList());
        
        return frameBuffer;
    }

    /**
     * Draws all organisms and tracks their positions for later cleanup.
     */
    private void drawOrganismsAndTrack(List<OrganismState> organisms) {
        for (OrganismState org : organisms) {
            // Track IP position
            int ipX = org.getIp().getComponents(0);
            int ipY = org.getIp().getComponents(1);
            int ipFlatIndex = coordinatesToFlatIndex(new int[]{ipX, ipY});
            previousOrganismPositions.add(new OrganismPosition(ipFlatIndex, ipX, ipY));
            
            if (org.getIsDead()) {
                // Dead organisms: draw IP as 4x larger marker
                drawLargeMarker(ipX, ipY, colorDead, 4);
            } else {
                Color orgColor = getOrganismColor(org.getOrganismId());

                // Track and draw Data Pointers (DPs)
                for (Vector dp : org.getDataPointersList()) {
                    int dpX = dp.getComponents(0);
                    int dpY = dp.getComponents(1);
                    int dpFlatIndex = coordinatesToFlatIndex(new int[]{dpX, dpY});
                    previousOrganismPositions.add(new OrganismPosition(dpFlatIndex, dpX, dpY));
                    drawLargeMarker(dpX, dpY, orgColor.getRGB(), 4);
                }

                // Draw Instruction Pointer (IP) as triangle/arrow pointing in DV direction
                int[] dv = new int[]{
                    org.getDv().getComponents(0),
                    org.getDv().getComponents(1)
                };
                drawTriangle(ipX, ipY, orgColor.getRGB(), 4, dv);
            }
        }
    }

    /**
     * Clears the area around an organism position by restoring cell colors.
     * Organisms are drawn larger than a single cell, so we need to restore
     * multiple cells around their position.
     */
    private void clearOrganismArea(OrganismPosition pos) {
        int markerSize = 4; // Same as used in drawing
        int offset = markerSize / 2;
        
        for (int dy = -offset; dy < offset; dy++) {
            for (int dx = -offset; dx < offset; dx++) {
                int cellX = pos.x + dx;
                int cellY = pos.y + dy;
                
                // Bounds check
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
        // Optimized: use Arrays.fill() per line instead of nested loops
        // This is much faster due to native optimized array filling
        for (int y = 0; y < cellSize; y++) {
            int startIndex = (startY + y) * imageWidth + startX;
            int endIndex = startIndex + cellSize;
            // No bounds check needed - coordinates come from valid TickData
            Arrays.fill(frameBuffer, startIndex, endIndex, color);
        }
    }

    /**
     * Draws a large marker centered at the given cell coordinates.
     * The marker is drawn as a square of sizeInCells × sizeInCells cells.
     *
     * @param cellX The X coordinate in cell space.
     * @param cellY The Y coordinate in cell space.
     * @param color The RGB color to use.
     * @param sizeInCells The size of the marker in cells (e.g., 4 = 4×4 cells).
     */
    private void drawLargeMarker(int cellX, int cellY, int color, int sizeInCells) {
        // Center the marker: offset by half the size
        int offset = sizeInCells / 2;
        int startCellX = cellX - offset;
        int startCellY = cellY - offset;
        
        int startX = startCellX * cellSize;
        int startY = startCellY * cellSize;
        int markerSizePixels = sizeInCells * cellSize;
        
        // Draw the marker using Arrays.fill() for performance
        for (int y = 0; y < markerSizePixels; y++) {
            int pixelY = startY + y;
            // Bounds check for Y to avoid array out of bounds
            if (pixelY < 0 || pixelY >= imageHeight) continue;
            
            int startIndex = pixelY * imageWidth + startX;
            int endIndex = startIndex + markerSizePixels;
            
            // Bounds check for X
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
        // moleculeType contains the bitmasked value (e.g., 0x00000, 0x10000, 0x20000, 0x30000)
        // from CellState.molecule_type, which is set as: moleculeInt & Config.TYPE_MASK
        // We need to compare directly with the Config constants or extract the raw type ID
        int moleculeType = moleculeInt & Config.TYPE_MASK;
        
        if (moleculeType == Config.TYPE_CODE) {
            return colorCodeBg;
        } else if (moleculeType == Config.TYPE_DATA) {
            return colorDataBg;
        } else if (moleculeType == Config.TYPE_ENERGY) {
            return colorEnergyBg;
        } else if (moleculeType == Config.TYPE_STRUCTURE) {
            return colorStructureBg;
        } else {
            return colorEmptyBg;
        }
    }

    /**
     * Gets the color for an organism based on its ID.
     * Uses the same logic as the web visualizer: (id - 1) % palette.length
     *
     * @param organismId The organism ID.
     * @return The color for this organism.
     */
    private Color getOrganismColor(int organismId) {
        return organismColorMap.computeIfAbsent(organismId, id -> {
            // Use same logic as web visualizer: (id - 1) % palette.length
            int paletteIndex = (id - 1) % organismColorPalette.length;
            if (paletteIndex < 0) paletteIndex = 0; // Handle edge case for id < 1
            return organismColorPalette[paletteIndex];
        });
    }

    /**
     * Draws a triangle/arrow pointing in the direction of the direction vector (DV).
     * If DV is zero or invalid, draws a circle instead.
     *
     * @param cellX The X coordinate in cell space.
     * @param cellY The Y coordinate in cell space.
     * @param color The RGB color to use.
     * @param sizeInCells The size of the marker in cells (e.g., 4 = 4×4 cells).
     * @param dv The direction vector [x, y].
     */
    private void drawTriangle(int cellX, int cellY, int color, int sizeInCells, int[] dv) {
        int centerX = cellX * cellSize + (cellSize / 2);
        int centerY = cellY * cellSize + (cellSize / 2);
        int halfSize = (sizeInCells * cellSize) / 2;
        
        if (dv != null && dv.length >= 2 && (dv[0] != 0 || dv[1] != 0)) {
            // Normalize direction vector
            double length = Math.sqrt(dv[0] * dv[0] + dv[1] * dv[1]);
            if (length > 0) {
                double dirX = dv[0] / length;
                double dirY = dv[1] / length;
                
                // Arrow tip (in direction of movement)
                int tipX = (int)(centerX + dirX * halfSize);
                int tipY = (int)(centerY + dirY * halfSize);
                
                // Arrow base points (perpendicular to direction)
                int base1X = (int)(centerX - dirX * halfSize + (-dirY) * halfSize);
                int base1Y = (int)(centerY - dirY * halfSize + dirX * halfSize);
                int base2X = (int)(centerX - dirX * halfSize - (-dirY) * halfSize);
                int base2Y = (int)(centerY - dirY * halfSize - dirX * halfSize);
                
                // Draw filled triangle using scanline algorithm
                drawFilledTriangle(tipX, tipY, base1X, base1Y, base2X, base2Y, color);
            } else {
                // Zero length DV: draw circle
                drawCircle(centerX, centerY, halfSize, color);
            }
        } else {
            // No direction: draw circle
            drawCircle(centerX, centerY, halfSize, color);
        }
    }

    /**
     * Draws a filled circle.
     *
     * @param centerX Center X coordinate in pixels.
     * @param centerY Center Y coordinate in pixels.
     * @param radius Radius in pixels.
     * @param color The RGB color to use.
     */
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

    /**
     * Draws a filled triangle using a scanline algorithm.
     *
     * @param x1 X coordinate of first vertex.
     * @param y1 Y coordinate of first vertex.
     * @param x2 X coordinate of second vertex.
     * @param y2 Y coordinate of second vertex.
     * @param x3 X coordinate of third vertex.
     * @param y3 Y coordinate of third vertex.
     * @param color The RGB color to use.
     */
    private void drawFilledTriangle(int x1, int y1, int x2, int y2, int x3, int y3, int color) {
        // Sort vertices by Y coordinate
        int[] xs = {x1, x2, x3};
        int[] ys = {y1, y2, y3};
        
        // Bubble sort by Y (simple for 3 elements)
        if (ys[0] > ys[1]) { int t = xs[0]; xs[0] = xs[1]; xs[1] = t; t = ys[0]; ys[0] = ys[1]; ys[1] = t; }
        if (ys[1] > ys[2]) { int t = xs[1]; xs[1] = xs[2]; xs[2] = t; t = ys[1]; ys[1] = ys[2]; ys[2] = t; }
        if (ys[0] > ys[1]) { int t = xs[0]; xs[0] = xs[1]; xs[1] = t; t = ys[0]; ys[0] = ys[1]; ys[1] = t; }
        
        int v1x = xs[0], v1y = ys[0];
        int v2x = xs[1], v2y = ys[1];
        int v3x = xs[2], v3y = ys[2];
        
        // Clamp Y coordinates to image bounds
        int minY = Math.max(0, v1y);
        int maxY = Math.min(imageHeight - 1, v3y);
        
        // Draw scanlines
        for (int y = minY; y <= maxY; y++) {
            int[] xCoords = new int[3];
            int count = 0;
            
            // Calculate X coordinates for this scanline using edge equations
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
                // Sort X coordinates and draw horizontal line
                Arrays.sort(xCoords, 0, count);
                int startX = Math.max(0, xCoords[0]);
                int endX = Math.min(imageWidth - 1, xCoords[count - 1]);
                
                if (startX <= endX) {
                    int startIndex = y * imageWidth + startX;
                    int endIndex = y * imageWidth + endX + 1;
                    Arrays.fill(frameBuffer, startIndex, endIndex, color);
                }
            }
        }
    }

    /**
     * Internal class to track organism positions for cleanup during incremental rendering.
     */
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
