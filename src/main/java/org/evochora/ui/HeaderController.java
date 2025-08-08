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
import org.evochora.Messages;
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
    private final Button zoomToggleButton; // NEW: Zoom toggle button
    private final Label tickLabel;

    private Runnable restartCallback;
    private boolean isZoomedOut = false; // NEW: Zoom status

    public HeaderController(Simulation simulation) {
        this.simulation = simulation;
        this.selectedOrganism = null;

        this.playPauseButton = new Button(Messages.get("header.button.playPause"));
        this.nextTickButton = new Button(Messages.get("header.button.nextTick"));
        this.restartButton = new Button(Messages.get("header.button.restart"));
        this.loggingToggleButton = new Button(Messages.get("header.button.loggingOff"));
        this.zoomToggleButton = new Button(Messages.get("header.button.zoomNormal")); // NEW: Initial text
        this.tickLabel = new Label(Messages.get("header.label.tick", 0));

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

        // CHANGED: Add zoom toggle to header
        headerPane.getChildren().addAll(playPauseButton, nextTickButton, restartButton, loggingToggleButton, zoomToggleButton, spacer3, tickLabel);
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

        // NEW: Action for the zoom toggle button
        zoomToggleButton.setOnAction(e -> {
            isZoomedOut = !isZoomedOut;
            zoomToggleButton.setText(isZoomedOut ? Messages.get("header.button.zoomPixel") : Messages.get("header.button.zoomNormal"));
        });
    }

    public void update(Simulation simulation, Organism selectedOrganism) {
        this.simulation = simulation;
        this.selectedOrganism = selectedOrganism;

        playPauseButton.setText(this.simulation.paused ? Messages.get("header.button.play") : Messages.get("header.button.pause"));

        if (selectedOrganism != null) {
            loggingToggleButton.setText(selectedOrganism.isLoggingEnabled() ? Messages.get("header.button.loggingOn") : Messages.get("header.button.loggingOff"));
            loggingToggleButton.setDisable(false);
        } else {
            loggingToggleButton.setText(Messages.get("header.button.loggingDisabled"));
            loggingToggleButton.setDisable(true);
        }

        tickLabel.setText(Messages.get("header.label.tick", this.simulation.getCurrentTick()));
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

    // NEW: Method to query zoom status from outside
    public boolean isZoomedOut() {
        return isZoomedOut;
    }
}