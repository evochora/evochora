// src/main/java/org/evochora/Renderer.java
package org.evochora;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.List;

public class Renderer {
    private final Canvas canvas;
    private final GraphicsContext gc;

    public Renderer(Canvas canvas) {
        this.canvas = canvas;
        this.gc = canvas.getGraphicsContext2D();
    }

    public void draw(Simulation simulation, Organism selectedOrganism) {
        gc.setFill(Color.rgb(10, 10, 20));
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        World world = simulation.getWorld();
        for (int x = 0; x < world.getShape()[0]; x++) {
            for (int y = 0; y < world.getShape()[1]; y++) {
                Symbol symbol = world.getSymbol(x, y);
                if (!symbol.isEmpty()) {
                    gc.setFill(getColorForSymbol(symbol));
                    gc.fillRect(x * Config.CELL_SIZE, y * Config.CELL_SIZE, Config.CELL_SIZE, Config.CELL_SIZE);
                }
            }
        }

        for (Organism org : simulation.getOrganisms()) {
            if (!org.isDead()) {
                drawOrganism(org);
            }
        }

        drawSidebar(simulation, selectedOrganism);
    }

    private void drawOrganism(Organism org) {
        // TODO:
        //  - Hier kommt später die Logik für Dreiecke etc.
        //  - Vorerst nur ein farbiger Punkt für den IP.
        gc.setFill(Color.RED);
        gc.fillOval(org.getIp()[0] * Config.CELL_SIZE, org.getIp()[1] * Config.CELL_SIZE, Config.CELL_SIZE, Config.CELL_SIZE);
    }

    private void drawSidebar(Simulation simulation, Organism selectedOrganism) {
        double sidebarX = Config.WORLD_SHAPE[0] * Config.CELL_SIZE;
        gc.setFill(Color.rgb(25, 25, 35));
        gc.fillRect(sidebarX, 0, Config.SIDEBAR_WIDTH, Config.SCREEN_HEIGHT);

        gc.setFill(Color.LIGHTGRAY);
        gc.setFont(Font.font("Monospaced", 14));

        String status = simulation.paused ? "PAUSED" : "RUNNING";
        gc.fillText("Status: " + status, sidebarX + 10, 20);
        gc.fillText("Tick: " + simulation.getCurrentTick(), sidebarX + 10, 40);

        if (selectedOrganism != null) {
            gc.fillText("Selected Organism:", sidebarX + 10, 80);
            gc.fillText("ID: " + selectedOrganism.getId(), sidebarX + 15, 100);
            gc.fillText("ER: " + selectedOrganism.getEr(), sidebarX + 15, 120);

            List<Object> drs = selectedOrganism.getDrs();
            for(int i = 0; i < drs.size(); i++) {
                gc.fillText(String.format("DR%d: %s", i, drs.get(i).toString()), sidebarX + 15, 140 + i * 20);
            }
        }
    }

    private Color getColorForSymbol(Symbol symbol) {
        return switch (symbol.type()) {
            case Config.TYPE_CODE -> Color.rgb(60, 80, 120);
            case Config.TYPE_DATA -> Color.rgb(100, 100, 100);
            case Config.TYPE_STRUCTURE -> Color.rgb(150, 150, 180);
            case Config.TYPE_ENERGY -> Color.rgb(200, 200, 50);
            default -> Color.rgb(30, 30, 40);
        };
    }
}