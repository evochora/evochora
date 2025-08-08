// src/main/java/org/evochora/ui/WorldRenderer.java
package org.evochora.ui;

import javafx.geometry.Rectangle2D;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.HashMap;
import java.util.Map;

public class WorldRenderer {
    private final Canvas canvas;
    private final GraphicsContext gc;
    private final Map<Integer, Color> organismColorMap = new HashMap<>();
    private final Color[] colorPalette = {
            Color.CRIMSON, Color.DODGERBLUE, Color.LIMEGREEN, Color.GOLD,
            Color.ORANGE, Color.MEDIUMPURPLE, Color.CYAN
    };
    private final Font cellFont;

    public WorldRenderer(Canvas canvas) {
        this.canvas = canvas;
        this.gc = canvas.getGraphicsContext2D();
        this.cellFont = Font.font("Monospaced", Config.CELL_SIZE * 0.4);
    }

    // CHANGED: The draw method now accepts an isZoomedOut parameter
    public void draw(Simulation simulation, Organism selectedOrganism, boolean isZoomedOut) {
        gc.setFill(Config.COLOR_BG);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        World world = simulation.getWorld();

        if (isZoomedOut) {
            drawZoomedOut(world, selectedOrganism);
        } else {
            drawNormal(world, selectedOrganism);
        }

        // Organisms are drawn in both modes, but the details differ.
        for (Organism org : simulation.getOrganisms()) {
            drawOrganism(org, selectedOrganism, isZoomedOut);
        }
    }

    private void drawNormal(World world, Organism selectedOrganism) {
        for (int x = 0; x < world.getShape()[0]; x++) {
            for (int y = 0; y < world.getShape()[1]; y++) {
                Symbol symbol = world.getSymbol(x, y);
                double cellX = x * Config.CELL_SIZE;
                double cellY = y * Config.CELL_SIZE;

                gc.setFill(getBackgroundColorForSymbol(symbol));
                gc.fillRect(cellX, cellY, Config.CELL_SIZE, Config.CELL_SIZE);
                drawCellText(symbol, cellX, cellY);
            }
        }
    }

    private void drawZoomedOut(World world, Organism selectedOrganism) {
        for (int x = 0; x < world.getShape()[0]; x++) {
            for (int y = 0; y < world.getShape()[1]; y++) {
                Symbol symbol = world.getSymbol(x, y);
                // Draw each cell as a 1x1 pixel
                gc.setFill(getBackgroundColorForSymbol(symbol));
                gc.fillRect(x, y, 1, 1);
            }
        }
    }


    private void drawCellText(Symbol symbol, double cellX, double cellY) {
        if (symbol.type() == Config.TYPE_CODE && symbol.value() == 0) {
            return;
        }

        gc.setFill(getTextColorForSymbol(symbol));
        gc.setFont(cellFont);
        gc.setTextAlign(TextAlignment.CENTER);

        String text;
        double centerX = cellX + Config.CELL_SIZE / 2.0;
        double y1 = cellY + Config.CELL_SIZE * 0.4;
        double y2 = cellY + Config.CELL_SIZE * 0.8;

        if (symbol.type() == Config.TYPE_CODE) {
            String fullName = Instruction.getInstructionNameById(symbol.toInt());

            if (fullName != null && !fullName.startsWith("UNKNOWN")) {
                String line1 = fullName.length() >= 2 ? fullName.substring(0, 2) : fullName;
                String line2 = fullName.length() > 2 ? fullName.substring(2) : "";
                gc.fillText(line1, centerX, y1);
                gc.fillText(line2, centerX, y2);
            } else {
                text = String.valueOf(symbol.value());
                gc.fillText(text, centerX, cellY + Config.CELL_SIZE / 2.0 + 4);
            }
        } else {
            // For all other types (DATA, ENERGY, etc.), always display the value.
            text = String.valueOf(symbol.value());
            gc.fillText(text, centerX, cellY + Config.CELL_SIZE / 2.0 + 4);
        }
    }

    // CHANGED: The drawOrganism method now accepts an isZoomedOut parameter
    private void drawOrganism(Organism org, Organism selectedOrganism, boolean isZoomedOut) {
        Color orgColor = organismColorMap.computeIfAbsent(org.getId(), id -> colorPalette[id % colorPalette.length]);
        int[] ip = org.getIp();
        double x = ip[0] * (isZoomedOut ? 1 : Config.CELL_SIZE); // Adjust scaling
        double y = ip[1] * (isZoomedOut ? 1 : Config.CELL_SIZE); // Adjust scaling

        // Border for the organism
        gc.setStroke(org.isDead() ? Config.COLOR_DEAD : orgColor);
        gc.setLineWidth(isZoomedOut ? 1 : 2.5);
        gc.strokeRect(x, y, isZoomedOut ? 1 : Config.CELL_SIZE, isZoomedOut ? 1 : Config.CELL_SIZE);

        // Highlight for the selected organism
        if (org == selectedOrganism && !org.isDead()) {
            // NEW: Only draw DP if not zoomed out
            if (!isZoomedOut) {
                int[] dp = org.getDp();
                double dpX = dp[0] * Config.CELL_SIZE;
                double dpY = dp[1] * Config.CELL_SIZE;
                drawDp(new Rectangle2D(dpX, dpY, Config.CELL_SIZE, Config.CELL_SIZE), orgColor);
            }
            // drawDv method handles zoom status internally
            drawDv(x, y, orgColor, org.getDv(), isZoomedOut);
        }
    }

    private void drawDp(Rectangle2D cellRect, Color color) {
        Color dpColor = color.deriveColor(0, 1.0, 1.2, 0.9);
        gc.setStroke(dpColor);
        gc.setLineWidth(1.5);
        double radius = Config.CELL_SIZE * 0.35;
        gc.strokeOval(cellRect.getMinX() + (Config.CELL_SIZE / 2.0) - radius,
                cellRect.getMinY() + (Config.CELL_SIZE / 2.0) - radius,
                radius * 2, radius * 2);
    }

    // CHANGED: The drawDv method now accepts an isZoomedOut parameter
    private void drawDv(double cellX, double cellY, Color color, int[] dv, boolean isZoomedOut) {
        if (isZoomedOut) {
            return; // No DV display in pixel mode
        }

        gc.setFill(color);
        double centerX = cellX + Config.CELL_SIZE / 2.0;
        double centerY = cellY + Config.CELL_SIZE / 2.0;
        double size = Config.CELL_SIZE * 0.2;
        double offset = Config.CELL_SIZE * 0.35;
        gc.fillOval(centerX + dv[0] * offset - size / 2, centerY + dv[1] * offset - size / 2, size, size);
    }

    private Color getBackgroundColorForSymbol(Symbol symbol) {
        return switch (symbol.type()) {
            case Config.TYPE_CODE -> {
                if (symbol.value() == 0) yield Config.COLOR_EMPTY_BG;
                else yield Config.COLOR_CODE_BG;
            }
            case Config.TYPE_DATA -> Config.COLOR_DATA_BG;
            case Config.TYPE_STRUCTURE -> Config.COLOR_STRUCTURE_BG;
            case Config.TYPE_ENERGY -> Config.COLOR_ENERGY_BG;
            default -> Config.COLOR_EMPTY_BG;
        };
    }

    private Color getTextColorForSymbol(Symbol symbol) {
        return switch (symbol.type()) {
            case Config.TYPE_STRUCTURE -> Config.COLOR_STRUCTURE_TEXT;
            case Config.TYPE_ENERGY -> Config.COLOR_ENERGY_TEXT;
            case Config.TYPE_DATA -> Config.COLOR_DATA_TEXT;
            case Config.TYPE_CODE -> Config.COLOR_CODE_TEXT;
            default -> Config.COLOR_TEXT;
        };
    }

    public Canvas getCanvas() {
        return canvas;
    }
}