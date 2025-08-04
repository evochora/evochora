// src/main/java/org/evochora/ui/HeaderController.java
package org.evochora.ui;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.organism.Organism;

public class HeaderController {

    private Simulation simulation;
    private Organism selectedOrganism;
    private final HBox headerPane;
    private final Button playPauseButton;
    private final Button nextTickButton;
    private final Button restartButton;
    private final Button loggingToggleButton;
    private final Label tickLabel;

    private Runnable restartCallback;

    public HeaderController(Simulation simulation) {
        this.simulation = simulation;
        this.selectedOrganism = null;

        this.playPauseButton = new Button("Play/Pause");
        this.nextTickButton = new Button("Next Tick");
        this.restartButton = new Button("Restart");
        this.loggingToggleButton = new Button("Logging: OFF");
        this.tickLabel = new Label("Tick: 0");

        setupButtons();

        headerPane = new HBox(10);
        headerPane.setPrefHeight(Config.HEADER_HEIGHT);
        headerPane.setStyle("-fx-background-color: " + toWebColor(Config.COLOR_HEADER_FOOTER) + "; -fx-padding: 10;");

        tickLabel.setFont(Font.font("Monospaced", 16));
        tickLabel.setTextFill(Config.COLOR_TEXT);

        Region spacer1 = new Region();
        HBox.setHgrow(spacer1, Priority.ALWAYS);
        Region spacer2 = new Region();
        HBox.setHgrow(spacer2, Priority.ALWAYS);
        Region spacer3 = new Region();
        HBox.setHgrow(spacer3, Priority.ALWAYS);

        headerPane.getChildren().addAll(playPauseButton, nextTickButton, restartButton, loggingToggleButton, spacer3, tickLabel);
    }

    private void setupButtons() {
        playPauseButton.setOnAction(e -> this.simulation.paused = !this.simulation.paused);
        nextTickButton.setOnAction(e -> {
            if (this.simulation.paused) {
                this.simulation.tick();
            }
        });
        restartButton.setOnAction(e -> {
            if (restartCallback != null) {
                restartCallback.run();
            }
        });

        loggingToggleButton.setOnAction(e -> {
            if (selectedOrganism != null) {
                selectedOrganism.setLoggingEnabled(!selectedOrganism.isLoggingEnabled());
                update(this.simulation, selectedOrganism);
            }
        });
    }

    public void update(Simulation simulation, Organism selectedOrganism) {
        this.simulation = simulation;
        this.selectedOrganism = selectedOrganism;

        playPauseButton.setText(this.simulation.paused ? "Play" : "Pause");

        if (selectedOrganism != null) {
            loggingToggleButton.setText("Logging: " + (selectedOrganism.isLoggingEnabled() ? "ON" : "OFF"));
            loggingToggleButton.setDisable(false);
        } else {
            loggingToggleButton.setText("Logging: ---");
            loggingToggleButton.setDisable(true);
        }

        tickLabel.setText("Tick: " + this.simulation.getCurrentTick());
    }

    public void updateSimulation(Simulation simulation) {
        this.simulation = simulation;
    }

    public void setRestartCallback(Runnable callback) {
        this.restartCallback = callback;
    }

    public HBox getView() {
        return headerPane;
    }

    private String toWebColor(Color color) {
        return String.format("#%02X%02X%02X",
                (int) (color.getRed() * 255),
                (int) (color.getGreen() * 255),
                (int) (color.getBlue() * 255));
    }
}