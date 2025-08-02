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
        gc.setFill(Config.COLOR_TEXT_IN_CELL);
        gc.setFont(cellFont);
        gc.setTextAlign(TextAlignment.CENTER);

        String text = "";
        Config.Opcode opcode = Config.OPCODE_DEFINITIONS.get(symbol.toInt());
        if (opcode != null) {
            String fullName = opcode.name();
            String line1 = fullName.length() >= 2 ? fullName.substring(0, 2) : fullName;
            String line2 = fullName.length() > 2 ? fullName.substring(2) : "";
            double centerX = cellX + Config.CELL_SIZE / 2.0;
            double y1 = cellY + Config.CELL_SIZE * 0.4;
            double y2 = cellY + Config.CELL_SIZE * 0.8;
            gc.fillText(line1, centerX, y1);
            gc.fillText(line2, centerX, y2);
        } else {
            text = String.valueOf(symbol.value());
            gc.fillText(text, cellX + Config.CELL_SIZE / 2.0, cellY + Config.CELL_SIZE / 2.0 + 4);
        }
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
            int[] dp = org.getDp();
            double dpX = dp[0] * Config.CELL_SIZE;
            double dpY = dp[1] * Config.CELL_SIZE + Config.HEADER_HEIGHT;
            drawDp(new javafx.geometry.Rectangle2D(dpX, dpY, Config.CELL_SIZE, Config.CELL_SIZE), orgColor);
            drawDv(x, y, orgColor, org.getDv());
        }
    }

    private void drawDp(javafx.geometry.Rectangle2D cellRect, Color color) {
        Color dpColor = color.deriveColor(0, 1.0, 1.2, 0.9);
        gc.setStroke(dpColor);
        gc.setLineWidth(1.5);
        double radius = Config.CELL_SIZE * 0.35;
        gc.strokeOval(cellRect.getMinX() + (Config.CELL_SIZE / 2.0) - radius,
                cellRect.getMinY() + (Config.CELL_SIZE / 2.0) - radius,
                radius * 2, radius * 2);
    }

    private void drawDv(double cellX, double cellY, Color color, int[] dv) {
        gc.setFill(color);
        double centerX = cellX + Config.CELL_SIZE / 2.0;
        double centerY = cellY + Config.CELL_SIZE / 2.0;
        double size = Config.CELL_SIZE * 0.2;
        double offset = Config.CELL_SIZE * 0.35;
        gc.fillOval(centerX + dv[0] * offset - size / 2, centerY + dv[1] * offset - size / 2, size, size);
    }

    private void drawHeader(Simulation simulation) {
        gc.setFill(Config.COLOR_HEADER_FOOTER);
        gc.fillRect(0, 0, Config.SCREEN_WIDTH, Config.HEADER_HEIGHT);
        gc.setFill(Config.COLOR_TEXT);
        gc.setFont(uiFont);
        gc.setTextAlign(TextAlignment.LEFT);
        String status = simulation.paused ? "PAUSED" : "RUNNING";
        gc.fillText("Status: " + status, 10, 30);
        gc.fillText("Tick: " + simulation.getCurrentTick(), 200, 30);
        gc.setStroke(Config.COLOR_TEXT);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.strokeRect(400, 10, 100, 30);
        gc.fillText("Play/Pause", 450, 30);
        gc.strokeRect(510, 10, 100, 30);
        gc.fillText("Next Tick", 560, 30);
        gc.strokeRect(620, 10, 100, 30);
        gc.fillText("Restart", 670, 30);
    }

    private void drawFooter(Simulation simulation, Organism selectedOrganism) {
        double footerY = Config.HEADER_HEIGHT + (Config.WORLD_SHAPE[1] * Config.CELL_SIZE);
        gc.setFill(Config.COLOR_HEADER_FOOTER);
        gc.fillRect(0, footerY, Config.SCREEN_WIDTH, Config.FOOTER_HEIGHT);

        if (selectedOrganism != null) {
            Color orgColor = organismColorMap.get(selectedOrganism.getId());
            if (orgColor == null) return;
            gc.setFill(selectedOrganism.isDead() ? Config.COLOR_DEAD : orgColor);
            gc.setFont(Font.font("Monospaced", 14));
            gc.setTextAlign(TextAlignment.LEFT);

            String line1 = String.format("ID: %d %s | ER: %d | IP: %s | DP: %s | DV: %s",
                    selectedOrganism.getId(), selectedOrganism.isDead() ? "(DEAD)" : "",
                    selectedOrganism.getEr(), Arrays.toString(selectedOrganism.getIp()),
                    Arrays.toString(selectedOrganism.getDp()), Arrays.toString(selectedOrganism.getDv()));
            gc.fillText(line1, 10, footerY + 20);

            List<Object> drs = selectedOrganism.getDrs();
            StringBuilder line2 = new StringBuilder("DRs: ");
            for(int i = 0; i < drs.size(); i++) {
                Object val = drs.get(i);
                // KORRIGIERT: Behandle Vektoren (int[]) in der Anzeige korrekt
                String valStr = (val instanceof int[]) ? Arrays.toString((int[]) val) : val.toString();
                line2.append(String.format("%d:[%s] ", i, valStr));
            }
            gc.fillText(line2.toString(), 10, footerY + 45);
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