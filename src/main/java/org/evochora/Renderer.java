// src/main/java/org/evochora/Renderer.java
package org.evochora;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.List;

public class Renderer {
    private final Canvas canvas;
    private final GraphicsContext gc;
    private final Config config;

    public Renderer(Canvas canvas, Config config) {
        this.canvas = canvas;
        this.gc = canvas.getGraphicsContext2D();
        this.config = config; // TODO: Renderer.config is not used and can be removed
    }

    public void draw(Simulation simulation, Organism selectedOrganism) {
        // 1. Hintergrund zeichnen
        gc.setFill(Color.rgb(10, 10, 20));
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        // 2. Welt-Gitter zeichnen
        World world = simulation.getWorld();
        for (int x = 0; x < world.getShape()[0]; x++) {
            for (int y = 0; y < world.getShape()[1]; y++) {
                int symbol = world.getSymbol(x, y);
                if (symbol > 0) { // Zeichne nur, was nicht leer ist
                    gc.setFill(getColorForSymbol(symbol));
                    gc.fillRect(x * Config.CELL_SIZE, y * Config.CELL_SIZE, Config.CELL_SIZE, Config.CELL_SIZE);
                }
            }
        }

        // 3. Organismen zeichnen
        for (Organism org : simulation.getOrganisms()) {
            if (!org.isDead()) {
                drawOrganism(org);
            }
        }

        // 4. Sidebar zeichnen (sehr einfach für den Anfang)
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

    private Color getColorForSymbol(int symbol) {
        if (symbol == Config.OP_SETL) {
            return Color.rgb(60, 80, 120); // Code-Farbe
        }
        return Color.rgb(100, 100, 100); // Daten-Farbe
    }
}