// src/main/java/org/evochora/Renderer.java
package org.evochora;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Renderer {
    private final Canvas canvas;
    private final GraphicsContext gc;
    private final Map<Integer, Color> organismColorMap = new HashMap<>();
    private final Color[] colorPalette = {
            Color.CRIMSON, Color.DODGERBLUE, Color.LIMEGREEN, Color.GOLD,
            Color.ORANGE, Color.MEDIUMPURPLE, Color.CYAN
    };
    private final Font cellFont;
    private final Font uiFont;

    public Renderer(Canvas canvas) {
        this.canvas = canvas;
        this.gc = canvas.getGraphicsContext2D();
        this.cellFont = Font.font("Monospaced", Config.CELL_SIZE * 0.4);
        this.uiFont = Font.font("Monospaced", 16);
    }

    public void draw(Simulation simulation, Organism selectedOrganism) {
        gc.setFill(Config.COLOR_BG);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        World world = simulation.getWorld();
        for (int x = 0; x < world.getShape()[0]; x++) {
            for (int y = 0; y < world.getShape()[1]; y++) {
                Symbol symbol = world.getSymbol(x, y);
                double cellX = x * Config.CELL_SIZE;
                double cellY = y * Config.CELL_SIZE + Config.HEADER_HEIGHT;

                gc.setFill(getColorForSymbol(symbol));
                gc.fillRect(cellX, cellY, Config.CELL_SIZE, Config.CELL_SIZE);

                drawCellText(symbol, cellX, cellY);
            }
        }

        for (Organism org : simulation.getOrganisms()) {
            drawOrganism(org, selectedOrganism);
        }

        drawHeader(simulation);
        drawFooter(simulation, selectedOrganism);
    }

    private void drawCellText(Symbol symbol, double cellX, double cellY) {
        if (symbol.isEmpty()) return;

        if (symbol.type() == Config.TYPE_CODE) {
            gc.setFill(Config.COLOR_TEXT);
        } else {
            gc.setFill(Config.COLOR_TEXT_IN_CELL);
        }

        gc.setFont(cellFont);
        gc.setTextAlign(TextAlignment.CENTER);

        double textHeight = gc.getFont().getSize();

        String text = "";
        Config.Opcode opcode = Config.OPCODE_DEFINITIONS.get(symbol.toInt());
        if (opcode != null) {
            String name = opcode.name();
            if (name.length() == 4) {
                String line1 = name.substring(0, 2);
                String line2 = name.substring(2, 4);

                double startY = cellY + Config.CELL_SIZE / 2.0 - textHeight + (textHeight * 0.9);

                gc.fillText(line1, cellX + Config.CELL_SIZE / 2.0, startY);
                gc.fillText(line2, cellX + Config.CELL_SIZE / 2.0, startY + textHeight);
                return;
            } else {
                text = name;
            }
        } else if (symbol.toInt() != 0) {
            text = String.valueOf(symbol.value());
        }

        double textY = cellY + Config.CELL_SIZE / 2.0 + textHeight * 0.35;
        gc.fillText(text, cellX + Config.CELL_SIZE / 2.0, textY);
    }

    private void drawOrganism(Organism org, Organism selectedOrganism) {
        Color orgColor = organismColorMap.computeIfAbsent(org.getId(), id -> colorPalette[id % colorPalette.length]);

        int[] ip = org.getIp();
        double x = ip[0] * Config.CELL_SIZE;
        double y = ip[1] * Config.CELL_SIZE + Config.HEADER_HEIGHT;

        gc.setStroke(org.isDead() ? Config.COLOR_DEAD : orgColor);
        gc.setLineWidth(2.5);
        gc.strokeRect(x + 1, y + 1, Config.CELL_SIZE - 2, Config.CELL_SIZE - 2);

        if (org == selectedOrganism && !org.isDead()) {
            drawDv(org, x, y);
        }
    }

    private void drawDv(Organism org, double cellX, double cellY) {
        Color orgColor = organismColorMap.computeIfAbsent(org.getId(), id -> colorPalette[id % colorPalette.length]);

        gc.setFill(orgColor);
        int[] dv = org.getDv();
        double centerX = cellX + Config.CELL_SIZE / 2.0;
        double centerY = cellY + Config.CELL_SIZE / 2.0;
        double size = Config.CELL_SIZE * 0.2;

        gc.fillOval(centerX + dv[0] * size - size/2, centerY + dv[1] * size - size/2, size, size);
    }

    private void drawHeader(Simulation simulation) {
        gc.setFill(Config.COLOR_HEADER_FOOTER);
        gc.fillRect(0, 0, canvas.getWidth(), Config.HEADER_HEIGHT);

        gc.setFill(Config.COLOR_TEXT);
        gc.setFont(uiFont);
        gc.setTextAlign(TextAlignment.LEFT);

        String status = simulation.paused ? "PAUSED" : "RUNNING";
        gc.fillText("Status: " + status, 10, 30);
        gc.fillText("Tick: " + simulation.getCurrentTick(), 200, 30);

        double buttonWidth = 100;
        double buttonHeight = 30;
        double buttonY = 10;
        double textOffset = 20;

        gc.setStroke(Config.COLOR_TEXT);
        gc.setTextAlign(TextAlignment.CENTER);

        // Play/Pause Button
        double playPauseX = 400;
        gc.strokeRect(playPauseX, buttonY, buttonWidth, buttonHeight);
        gc.fillText("Play/Pause", playPauseX + buttonWidth / 2, buttonY + textOffset);

        // Next Tick Button
        double nextTickX = 510;
        gc.strokeRect(nextTickX, buttonY, buttonWidth, buttonHeight);
        gc.fillText("Next Tick", nextTickX + buttonWidth / 2, buttonY + textOffset);

        // Restart Button
        double restartX = 620;
        gc.strokeRect(restartX, buttonY, buttonWidth, buttonHeight);
        gc.fillText("Restart", restartX + buttonWidth / 2, buttonY + textOffset);
    }

    private void drawFooter(Simulation simulation, Organism selectedOrganism) {
        double footerY = Config.HEADER_HEIGHT + (Config.WORLD_SHAPE[1] * Config.CELL_SIZE);
        gc.setFill(Config.COLOR_HEADER_FOOTER);
        gc.fillRect(0, footerY, canvas.getWidth(), Config.FOOTER_HEIGHT);

        if (selectedOrganism != null) {
            Color orgColor = organismColorMap.computeIfAbsent(selectedOrganism.getId(), id -> colorPalette[id % colorPalette.length]);
            gc.setFill(selectedOrganism.isDead() ? Config.COLOR_DEAD : orgColor);
            gc.setFont(uiFont);
            gc.setTextAlign(TextAlignment.LEFT);

            String line1 = String.format("ID: %d %s | ER: %d | IP: %s",
                    selectedOrganism.getId(), selectedOrganism.isDead() ? "(DEAD)" : "",
                    selectedOrganism.getEr(), Arrays.toString(selectedOrganism.getIp()));
            // Position der ersten Zeile: 15 Pixel von oben im Footer
            gc.fillText(line1, 10, footerY + 18); // Angepasst von 20 auf 18

            List<Object> drs = selectedOrganism.getDrs();
            StringBuilder line2 = new StringBuilder("DRs: ");
            for(int i = 0; i < drs.size(); i++) {
                line2.append(String.format("%d:[%s] ", i, drs.get(i).toString()));
            }
            // Position der zweiten Zeile: 15 Pixel unterhalb der ersten Zeile
            gc.fillText(line2.toString(), 10, footerY + 18 + uiFont.getSize() + 8); // uiFont.getSize() + 8 für mehr Abstand
        }
    }

    private Color getColorForSymbol(Symbol symbol) {
        if (symbol.isEmpty()) return Config.COLOR_EMPTY;
        return switch (symbol.type()) {
            case Config.TYPE_CODE -> Config.COLOR_CODE;
            case Config.TYPE_DATA -> Config.COLOR_DATA;
            case Config.TYPE_STRUCTURE -> Config.COLOR_STRUCTURE;
            case Config.TYPE_ENERGY -> Config.COLOR_ENERGY;
            default -> Config.COLOR_EMPTY;
        };
    }
}