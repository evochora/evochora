// src/main/java/org/evochora/ui/FooterController.java
package org.evochora.ui;

import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import org.evochora.Config;
import org.evochora.Logger;
import org.evochora.organism.Organism;

import java.util.Arrays;
import java.util.List;

public class FooterController {

    private Logger logger;
    private final VBox footerPane;
    private final TextArea fullDetailsTextArea;

    public FooterController(Logger logger) {
        this.logger = logger;

        this.fullDetailsTextArea = new TextArea();

        setupFullDetailsTextArea();

        footerPane = new VBox(0);
        footerPane.setPrefHeight(Config.FOOTER_HEIGHT);
        footerPane.setStyle("-fx-background-color: " + toWebColor(Config.COLOR_HEADER_FOOTER) + "; -fx-padding: 10;");

        footerPane.getChildren().addAll(fullDetailsTextArea);

        update(null, logger);
    }

    private void setupFullDetailsTextArea() {
        Font uiFont = Font.font("Monospaced", 12);
        Color textColor = Config.COLOR_TEXT;

        fullDetailsTextArea.setFont(uiFont);
        fullDetailsTextArea.setEditable(false);
        fullDetailsTextArea.setWrapText(true);
        fullDetailsTextArea.setPrefRowCount(5);
        fullDetailsTextArea.setStyle("-fx-control-inner-background: " + toWebColor(Config.COLOR_HEADER_FOOTER) + ";" +
                "-fx-text-fill: " + toWebColor(textColor) + ";" +
                "-fx-background-color: transparent; -fx-border-width: 0;");
    }

    /**
     * Aktualisiert die Anzeige im Footer.
     * @param selectedOrganism Der aktuell ausgewählte Organismus (kann null sein).
     * @param logger Die Logger-Instanz.
     */
    public void update(Organism selectedOrganism, Logger logger) {
        this.logger = logger;

        StringBuilder displayText = new StringBuilder();

        if (selectedOrganism != null) {
            Color orgColor = selectedOrganism.isDead() ? Config.COLOR_DEAD : Config.COLOR_TEXT;
            fullDetailsTextArea.setStyle("-fx-control-inner-background: " + toWebColor(Config.COLOR_HEADER_FOOTER) + ";" +
                    "-fx-text-fill: " + toWebColor(orgColor) + ";" +
                    "-fx-background-color: transparent; -fx-border-width: 0;");

            displayText.append(String.format("ID: %d %s | ER: %d | IP: %s | DP: %s | DV: %s\n",
                    selectedOrganism.getId(), selectedOrganism.isDead() ? "(DEAD)" : "",
                    selectedOrganism.getEr(), Arrays.toString(selectedOrganism.getIp()),
                    Arrays.toString(selectedOrganism.getDp()), Arrays.toString(selectedOrganism.getDv())));

            List<Object> drs = selectedOrganism.getDrs();
            StringBuilder drsText = new StringBuilder("DRs: ");
            for(int i = 0; i < drs.size(); i++) {
                Object val = drs.get(i);
                drsText.append(String.format("%d=%s", i, this.logger.formatDrValue(val)));
                if (i < drs.size() - 1) {
                    drsText.append(", ");
                }
            }
            displayText.append(drsText.toString()).append("\n");

            String stackText = "Stack: " + logger.formatStack(selectedOrganism, true, 8);
            displayText.append(stackText).append("\n");

            displayText.append("Next: ").append(logger.getNextInstructionInfo(selectedOrganism));

        } else {
            fullDetailsTextArea.setStyle("-fx-control-inner-background: " + toWebColor(Config.COLOR_HEADER_FOOTER) + ";" +
                    "-fx-text-fill: " + toWebColor(Config.COLOR_TEXT) + ";" +
                    "-fx-background-color: transparent; -fx-border-width: 0;");

            displayText.append("No Organism Selected.\nDRs: \nStack: ---\nNext: ---");
        }
        fullDetailsTextArea.setText(displayText.toString());
    }

    public void updateLogger(Logger logger) {
        this.logger = logger;
    }

    public VBox getView() {
        return footerPane;
    }

    private String toWebColor(Color color) {
        return String.format("#%02X%02X%02X",
                (int) (color.getRed() * 255),
                (int) (color.getGreen() * 255),
                (int) (color.getBlue() * 255));
    }
}