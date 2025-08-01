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
import org.evochora.world.Symbol;

import java.util.Arrays;

public class Main extends Application {

    private Simulation simulation;
    private Renderer renderer;
    private Organism selectedOrganism = null;
    private Canvas canvas; // Canvas bleibt Instanzvariable für Renderer-Zugriff

    @Override
    public void start(Stage primaryStage) {
        try {
            World world = new World(Config.WORLD_SHAPE, Config.IS_TOROIDAL);
            this.simulation = new Simulation(world);

            Setup.run(this.simulation);

            // Canvas wird wieder mit festen Config-Größen initialisiert
            canvas = new Canvas(Config.SCREEN_WIDTH, Config.SCREEN_HEIGHT);
            this.renderer = new Renderer(canvas);

            HBox root = new HBox(canvas);
            Scene scene = new Scene(root);

            // Fenstergröße auf die Größe des Inhalts beschränken (kein Vergrößern über die Welt hinaus)
            primaryStage.setMinWidth(Config.SCREEN_WIDTH);
            primaryStage.setMinHeight(Config.SCREEN_HEIGHT);
            primaryStage.setMaxWidth(Config.SCREEN_WIDTH);
            primaryStage.setMaxHeight(Config.SCREEN_HEIGHT);

            scene.setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.ENTER) {
                    simulation.paused = !simulation.paused;
                }
                if (event.getCode() == KeyCode.SPACE && simulation.paused) {
                    simulation.tick();
                }
                if (event.getCode() == KeyCode.R) {
                    restartSimulation();
                }
            });

            // MouseEvent-Handler direkt auf den Canvas legen
            canvas.setOnMouseClicked(event -> {
                double mouseX = event.getX();
                double mouseY = event.getY();

                // Klick-Logik für Header-Buttons (auf Canvas-Ebene)
                // Die Y-Koordinaten für Buttons beziehen sich immer auf den Canvas selbst, beginnend bei 0
                if (mouseY >= 10 && mouseY <= 40) { // Bereich der Buttons
                    if (mouseX >= 400 && mouseX <= 500) { // Play/Pause Button
                        simulation.paused = !simulation.paused;
                    } else if (mouseX >= 510 && mouseX <= 610) { // Next Tick Button
                        if (simulation.paused) {
                            simulation.tick();
                        }
                    } else if (mouseX >= 620 && mouseX <= 720) { // Restart Button (alte Position wiederhergestellt)
                        restartSimulation();
                    }
                    // Zoom-Button-Logik wurde entfernt
                }

                // Klick-Logik für Zellen (Header-Offset berücksichtigen)
                double yOffset = event.getY() - Config.HEADER_HEIGHT;
                int gridX = (int) (event.getX() / Config.CELL_SIZE); // Wieder Config.CELL_SIZE verwenden
                int gridY = (int) (yOffset / Config.CELL_SIZE);       // Wieder Config.CELL_SIZE verwenden

                if (yOffset > 0 && gridX < Config.WORLD_SHAPE[0] && gridY < Config.WORLD_SHAPE[1]) {
                    int[] clickedCoord = {gridX, gridY};
                    this.selectedOrganism = null;
                    for (Organism org : simulation.getOrganisms()) {
                        if (Arrays.equals(org.getIp(), clickedCoord)) {
                            this.selectedOrganism = org;
                            break;
                        }
                    }
                }
            });

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

        } catch (Exception e) {
            e.printStackTrace();
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Ein Fehler ist aufgetreten");
            alert.setHeaderText("Die Anwendung muss beendet werden.");
            alert.setContentText("Fehler: " + e.getMessage());
            alert.showAndWait();
        }
    }

    private void restartSimulation() {
        try {
            World newWorld = new World(Config.WORLD_SHAPE, Config.IS_TOROIDAL);
            this.simulation = new Simulation(newWorld);
            Setup.run(this.simulation);
            this.selectedOrganism = null;
            this.simulation.paused = true;
            // updateCanvasAndScrollPaneSize() wird nicht mehr benötigt
        } catch (Exception e) {
            e.printStackTrace();
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Neustart Fehler");
            alert.setHeaderText("Die Simulation konnte nicht neu gestartet werden.");
            alert.setContentText("Fehler: " + e.getMessage());
            alert.showAndWait();
        }
    }

    // Die Methode updateCanvasAndScrollPaneSize() wurde entfernt.

    public static void main(String[] args) {
        launch(args);
    }
}