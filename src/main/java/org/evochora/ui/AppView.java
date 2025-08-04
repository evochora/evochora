// src/main/java/org/evochora/ui/AppView.java
package org.evochora.ui;

import javafx.animation.AnimationTimer;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.Setup;
import org.evochora.world.World;
import org.evochora.organism.Organism;

import java.util.Arrays;

public class AppView {

    private Simulation simulation;
    private final WorldRenderer worldRenderer;
    private Organism selectedOrganism = null;
    private final Stage primaryStage;
    private final BorderPane root;

    private final HeaderController headerController;
    private final FooterController footerController;

    public AppView(Stage primaryStage, Simulation simulation) {
        this.primaryStage = primaryStage;
        this.simulation = simulation;

        // GEÄNDERT: Canvas Größe nur noch für die Welt selbst
        Canvas worldCanvas = new Canvas(Config.WORLD_SHAPE[0] * Config.CELL_SIZE,
                Config.WORLD_SHAPE[1] * Config.CELL_SIZE);
        this.worldRenderer = new WorldRenderer(worldCanvas);

        this.headerController = new HeaderController(this.simulation);
        this.footerController = new FooterController(this.simulation.getLogger());

        root = new BorderPane();
        root.setCenter(worldCanvas); // Canvas in die Mitte setzen
        root.setTop(headerController.getView()); // Header oben platzieren
        root.setBottom(footerController.getView()); // Footer unten platzieren

        setupPrimaryStage();
        setupCanvasInteraction(worldCanvas);
        setupHeaderRestartCallback();
        startSimulationLoop();
    }

    private void setupPrimaryStage() {
        // GEÄNDERT: Mindestgröße des Fensters basierend auf den Komponenten
        primaryStage.setMinWidth(Config.WORLD_SHAPE[0] * Config.CELL_SIZE);
        primaryStage.setMinHeight(Config.WORLD_SHAPE[1] * Config.CELL_SIZE + Config.HEADER_HEIGHT + Config.FOOTER_HEIGHT);
        primaryStage.setTitle("Evochora");
        primaryStage.setScene(new Scene(root));
        primaryStage.show();
    }

    private void setupCanvasInteraction(Canvas canvas) {
        canvas.setOnMouseClicked(event -> {
            double mouseX = event.getX();
            double mouseY = event.getY();

            // KORRIGIERT: Klick-Logik nur für den reinen Welt-Canvas-Bereich
            // Es gibt keine Header/Footer-Offsets mehr für den Canvas selbst.
            double worldWidth = Config.WORLD_SHAPE[0] * Config.CELL_SIZE;
            double worldHeight = Config.WORLD_SHAPE[1] * Config.CELL_SIZE;

            if (mouseX >= 0 && mouseX < worldWidth && mouseY >= 0 && mouseY < worldHeight) {

                int gridX = (int) (mouseX / Config.CELL_SIZE);
                int gridY = (int) (mouseY / Config.CELL_SIZE); // KEIN Y-OFFSET MEHR

                int[] clickedCoord = {gridX, gridY};
                this.selectedOrganism = null;
                for (Organism org : simulation.getOrganisms()) {
                    if (Arrays.equals(org.getIp(), clickedCoord)) {
                        this.selectedOrganism = org;
                        break;
                    }
                }
                headerController.update(simulation, selectedOrganism);
                footerController.update(selectedOrganism, simulation.getLogger());
            }
        });
        primaryStage.getScene().setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER) {
                this.simulation.paused = !this.simulation.paused;
                headerController.update(this.simulation, selectedOrganism);
            }
            if (event.getCode() == javafx.scene.input.KeyCode.SPACE && this.simulation.paused) {
                this.simulation.tick();
                worldRenderer.draw(this.simulation, selectedOrganism);
                headerController.update(this.simulation, selectedOrganism);
                footerController.update(selectedOrganism, this.simulation.getLogger());
            }
            if (event.getCode() == javafx.scene.input.KeyCode.R) {
                restartSimulation();
            }
        });
    }

    private void setupHeaderRestartCallback() {
        headerController.setRestartCallback(this::restartSimulation);
    }

    private void startSimulationLoop() {
        new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (!AppView.this.simulation.paused) {
                    AppView.this.simulation.tick();
                }
                worldRenderer.draw(AppView.this.simulation, selectedOrganism);
                headerController.update(AppView.this.simulation, selectedOrganism);
                footerController.update(selectedOrganism, AppView.this.simulation.getLogger());
            }
        }.start();
    }

    private void restartSimulation() {
        try {
            World newWorld = new World(Config.WORLD_SHAPE, Config.IS_TOROIDAL);
            this.simulation = new Simulation(newWorld);
            Setup.run(this.simulation);
            this.selectedOrganism = null;
            this.simulation.paused = true;

            headerController.updateSimulation(this.simulation);
            footerController.updateLogger(this.simulation.getLogger());

            headerController.update(this.simulation, null);
            footerController.update(null, this.simulation.getLogger());
        } catch (Exception e) {
            e.printStackTrace();
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
            alert.setTitle("Neustart Fehler");
            alert.setHeaderText("Die Simulation konnte nicht neu gestartet werden.");
            alert.setContentText("Fehler: " + e.getMessage());
            alert.showAndWait();
        }
    }

    public BorderPane getRoot() {
        return root;
    }

    public Simulation getSimulation() {
        return simulation;
    }

    public Organism getSelectedOrganism() {
        return selectedOrganism;
    }

    public void setSelectedOrganism(Organism organism) {
        this.selectedOrganism = organism;
    }
}