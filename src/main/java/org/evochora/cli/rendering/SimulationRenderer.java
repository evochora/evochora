package org.evochora.cli.rendering;

import org.evochora.datapipeline.api.contracts.CellState;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.EnvironmentProperties;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Renders a single simulation tick to an image buffer.
 * <p>
 * This class uses the same color palette as the web visualizer to produce
 * visually consistent output. It is optimized for performance by drawing
 * directly to the pixel buffer of a BufferedImage.
 */
public class SimulationRenderer {

    private final EnvironmentProperties envProps;
    private final int cellSize;
    private final int imageWidth;
    private final int imageHeight;
    private final BufferedImage frame;
    private final int[] frameBuffer;

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
    
    private final Map<String, Color> programColorMap = new HashMap<>();
    private int nextColorIndex = 0;

    /**
     * Creates a new renderer for a simulation run.
     *
     * @param envProps Environment properties (world shape, topology).
     * @param cellSize The size of each cell in pixels.
     */
    public SimulationRenderer(EnvironmentProperties envProps, int cellSize) {
        this.envProps = envProps;
        this.cellSize = cellSize;
        this.imageWidth = envProps.getWorldShape()[0] * cellSize;
        this.imageHeight = envProps.getWorldShape()[1] * cellSize;

        this.frame = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_RGB);
        this.frameBuffer = ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();
    }

    /**
     * Renders a single tick into an array of pixel data.
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
        for (CellState cell : tick.getCellsList()) {
            int[] coord = envProps.flatIndexToCoordinates(cell.getFlatIndex());
            
            // For 2D rendering, use first two coordinates
            // Note: This assumes 2D world for video rendering (which is standard)
            int x = coord[0];
            int y = coord[1];
            
            int color = getCellColor(cell.getMoleculeType());
            drawCell(x, y, color);
        }

        // 3. Draw organisms
        for (OrganismState org : tick.getOrganismsList()) {
            if (org.getIsDead()) {
                drawCell(org.getIp().getComponents(0), org.getIp().getComponents(1), colorDead);
            } else {
                Color orgColor = getOrganismColor(org.getProgramId());

                // Draw Data Pointers (DPs) in a darker shade
                Color dpColor = orgColor.darker();
                for (Vector dp : org.getDataPointersList()) {
                    drawCell(dp.getComponents(0), dp.getComponents(1), dpColor.getRGB());
                }

                // Draw Instruction Pointer (IP) on top
                drawCell(org.getIp().getComponents(0), org.getIp().getComponents(1), orgColor.getRGB());
            }
        }
        
        return frameBuffer;
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

    private int getCellColor(int moleculeType) {
        // moleculeType contains the bitmasked value (e.g., 0x00000, 0x10000, 0x20000, 0x30000)
        // from CellState.molecule_type, which is set as: moleculeInt & Config.TYPE_MASK
        // We need to compare directly with the Config constants or extract the raw type ID
        
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

    private Color getOrganismColor(String programId) {
        return programColorMap.computeIfAbsent(programId, k -> {
            Color color = organismColorPalette[nextColorIndex];
            nextColorIndex = (nextColorIndex + 1) % organismColorPalette.length;
            return color;
        });
    }
}
