// src/main/java/org/evochora/Main.java
package org.evochora;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import java.util.Arrays; // Import für Arrays.copyOf

public class Main extends Application {

    private Simulation simulation;
    private Renderer renderer;
    private Organism selectedOrganism = null;

    // TODO: Make simulation runnable without renderer and use make renderer to use a replay log to display simulation
    @Override
    public void start(Stage primaryStage) {
        // --- Setup der Simulation ---
        // TODO: Use Config only static
        Config config = new Config(); // Wird an den Renderer übergeben
        World world = new World(Config.WORLD_SHAPE, Config.IS_TOROIDAL);
        this.simulation = new Simulation(world);

        // Platziere den ersten Test-Organismus
        Setup.run(this.simulation);

        // --- Setup der grafischen Oberfläche ---
        Canvas canvas = new Canvas(Config.SCREEN_WIDTH, Config.SCREEN_HEIGHT);
        this.renderer = new Renderer(canvas, config);

        HBox root = new HBox(canvas);
        Scene scene = new Scene(root);

        // --- Event-Handling (Tastatur & Maus) ---
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.SPACE) {
                simulation.paused = !simulation.paused;
            }
            if (event.getCode() == KeyCode.ENTER && simulation.paused) {
                simulation.tick();
            }
        });

        scene.setOnMouseClicked(event -> {
            int gridX = (int) (event.getX() / Config.CELL_SIZE);
            int gridY = (int) (event.getY() / Config.CELL_SIZE);
            int[] clickedCoord = {gridX, gridY};

            this.selectedOrganism = null;
            for (Organism org : simulation.getOrganisms()) {
                if (!org.isDead() && Arrays.equals(org.getIp(), clickedCoord)) {
                    this.selectedOrganism = org;
                    break;
                }
            }
        });

        // --- Haupt-Schleife (AnimationTimer) ---
        new AnimationTimer() {
            @Override
            public void handle(long now) {
                // Führe die Simulationslogik nur aus, wenn nicht pausiert ist
                if (!simulation.paused) {
                    simulation.tick();
                }
                // Zeichne immer den aktuellen Zustand, auch im Pause-Modus
                renderer.draw(simulation, selectedOrganism);
            }
        }.start();

        primaryStage.setTitle("Evochora");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        // Startet die JavaFX-Anwendung
        launch(args);
    }
}