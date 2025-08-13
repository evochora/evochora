package org.evochora.app.ui;

import javafx.animation.AnimationTimer;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import org.evochora.app.i18n.LocalizationProvider;
import org.evochora.app.setup.Config;
import org.evochora.app.Simulation;
import org.evochora.app.setup.Setup;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Organism;

import java.util.Arrays;

public class AppView {

    private Simulation simulation;
    private final WorldRenderer worldRenderer;
    private Organism selectedOrganism = null;
    private final Stage primaryStage;
    private final BorderPane root;
    private final ScrollPane scrollPane;

    private final HeaderController headerController;
    private final FooterController footerController;

    public AppView(Stage primaryStage, Simulation simulation) {
        this.primaryStage = primaryStage;
        this.simulation = simulation;

        double initialCanvasWidth = Config.WORLD_SHAPE[0] * Config.CELL_SIZE;
        double initialCanvasHeight = Config.WORLD_SHAPE[1] * Config.CELL_SIZE;

        Canvas worldCanvas = new Canvas(initialCanvasWidth, initialCanvasHeight);
        this.worldRenderer = new WorldRenderer(worldCanvas);

        this.headerController = new HeaderController(this.simulation);
        this.footerController = new FooterController();

        scrollPane = new ScrollPane();
        scrollPane.setContent(worldCanvas);
        scrollPane.setPannable(true);

        root = new BorderPane();
        root.setCenter(scrollPane);
        root.setTop(headerController.getView());
        root.setBottom(footerController.getView());

        setupPrimaryStage();
        setupCanvasInteraction(worldCanvas);
        setupHeaderRestartCallback();
        startSimulationLoop();
    }

    private void setupPrimaryStage() {
        primaryStage.setTitle("Evochora");
        primaryStage.setScene(new Scene(root, 1800, 1400));

        // CORRECTED: Additional buffer for the maximum size to compensate for layout differences.
        // The buffer was increased from 20 to 40 to ensure that the scrollbars are not visible.
        double maxWorldWidth = Config.WORLD_SHAPE[0] * Config.CELL_SIZE + 20;
        double maxWorldHeight = Config.WORLD_SHAPE[1] * Config.CELL_SIZE + Config.HEADER_HEIGHT + Config.FOOTER_HEIGHT + 42;
        primaryStage.setMaxWidth(maxWorldWidth);
        primaryStage.setMaxHeight(maxWorldHeight);

        primaryStage.show();
    }

    private void setupCanvasInteraction(Canvas canvas) {
        canvas.setOnMouseClicked(event -> {
            double mouseX = event.getX();
            double mouseY = event.getY();

            double scale = headerController.isZoomedOut() ? 1.0 : Config.CELL_SIZE;

            double worldWidth = Config.WORLD_SHAPE[0] * scale;
            double worldHeight = Config.WORLD_SHAPE[1] * scale;

            if (mouseX >= 0 && mouseX < worldWidth && mouseY >= 0 && mouseY < worldHeight) {

                int gridX = (int) (mouseX / scale);
                int gridY = (int) (mouseY / scale);

                int[] clickedCoord = {gridX, gridY};
                this.selectedOrganism = null;
                for (Organism org : simulation.getOrganisms()) {
                    if (Arrays.equals(org.getIp(), clickedCoord)) {
                        this.selectedOrganism = org;
                        break;
                    }
                }
                headerController.update(simulation, selectedOrganism);
                footerController.update(simulation, selectedOrganism);
            }
        });
        primaryStage.getScene().setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER) {
                this.simulation.paused = !this.simulation.paused;
                headerController.update(this.simulation, selectedOrganism);
            }
            if (event.getCode() == javafx.scene.input.KeyCode.SPACE && this.simulation.paused) {
                this.simulation.tick();
                worldRenderer.draw(this.simulation, selectedOrganism, headerController.isZoomedOut());
                headerController.update(this.simulation, selectedOrganism);
                footerController.update(this.simulation, selectedOrganism);
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
                double scale = headerController.isZoomedOut() ? 1.0 : Config.CELL_SIZE;
                double newWidth = Config.WORLD_SHAPE[0] * scale;
                double newHeight = Config.WORLD_SHAPE[1] * scale;
                worldRenderer.getCanvas().setWidth(newWidth);
                worldRenderer.getCanvas().setHeight(newHeight);

                if (!AppView.this.simulation.paused) {
                    AppView.this.simulation.tick();
                }
                worldRenderer.draw(AppView.this.simulation, selectedOrganism, headerController.isZoomedOut());
                headerController.update(AppView.this.simulation, selectedOrganism);
                footerController.update(AppView.this.simulation, selectedOrganism);
            }
        }.start();
    }

    private void restartSimulation() {
        try {
            Environment newEnvironment = new Environment(Config.WORLD_SHAPE, Config.IS_TOROIDAL);
            this.simulation = new Simulation(newEnvironment);
            Setup.run(this.simulation);
            this.selectedOrganism = null;
            this.simulation.paused = true;

            headerController.updateSimulation(this.simulation);

            headerController.update(this.simulation, null);
            footerController.update(this.simulation, null);
        } catch (Exception e) {
            e.printStackTrace();
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
            alert.setTitle(LocalizationProvider.getString("appView.restartError.title"));
            alert.setHeaderText(LocalizationProvider.getString("appView.restartError.header"));
            alert.setContentText(LocalizationProvider.getString("appView.restartError.content") + "\n\n" + e.getMessage());
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