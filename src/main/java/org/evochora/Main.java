// src/main/java/org/evochora/Main.java
package org.evochora;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Alert;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.evochora.organism.Organism;
import org.evochora.world.World;
import org.evochora.world.Symbol; // Import Symbol as well, as it might be needed indirectly.

import java.util.Arrays;

public class Main extends Application {

    private Simulation simulation;
    private Renderer renderer;
    private Organism selectedOrganism = null;

    @Override
    public void start(Stage primaryStage) {
        try {
            // Setup der Simulation
            World world = new World(Config.WORLD_SHAPE, Config.IS_TOROIDAL);
            this.simulation = new Simulation(world);

            Setup.run(this.simulation);

            // Setup der grafischen Oberfläche
            Canvas canvas = new Canvas(Config.SCREEN_WIDTH, Config.SCREEN_HEIGHT);
            this.renderer = new Renderer(canvas);

            HBox root = new HBox(canvas);
            Scene scene = new Scene(root);

            // Event-Handling
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

            // Haupt-Schleife
            new AnimationTimer() {
                @Override
                public void handle(long now) {
                    if (!simulation.paused) {
                        simulation.tick();
                    }
                    renderer.draw(simulation, selectedOrganism);
                }
            }.start();

            primaryStage.setTitle("Evochora");
            primaryStage.setScene(scene);
            primaryStage.show();

        } catch (IllegalArgumentException e) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Konfigurationsfehler");
            alert.setHeaderText("Die Simulation konnte nicht gestartet werden.");
            alert.setContentText(e.getMessage() + "\n\nBitte passen Sie die Config.java an.");
            alert.showAndWait();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}