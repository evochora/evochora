// src/main/java/org/evochora/ui/FooterController.java
package org.evochora.ui;

import javafx.scene.control.Label; // Wird nicht mehr direkt verwendet, aber der Import kann bleiben
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField; // Wird nicht mehr direkt verwendet, aber der Import kann bleiben
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
    // GEÄNDERT: Nur noch ein großes TextArea für alle Informationen
    private final TextArea fullDetailsTextArea;

    public FooterController(Logger logger) {
        this.logger = logger;

        // GEÄNDERT: Initialisierung des einzelnen TextAreas
        this.fullDetailsTextArea = new TextArea();

        setupFullDetailsTextArea(); // NEU: Setup-Methode für das einzelne TextArea

        // Layout für den Footer
        // VBox spacing wird irrelevant, da nur ein Child
        footerPane = new VBox(0); // Abstand auf 0, da nur ein Child
        footerPane.setPrefHeight(Config.FOOTER_HEIGHT);
        footerPane.setStyle("-fx-background-color: " + toWebColor(Config.COLOR_HEADER_FOOTER) + "; -fx-padding: 10;");

        // GEÄNDERT: Nur das eine TextArea zum Footer hinzufügen
        footerPane.getChildren().addAll(fullDetailsTextArea);

        update(null, logger);
    }

    // NEU: Setup-Methode für das einzige TextArea
    private void setupFullDetailsTextArea() {
        Font uiFont = Font.font("Monospaced", 12); // Etwas kleinere Schrift für mehr Text
        Color textColor = Config.COLOR_TEXT;

        fullDetailsTextArea.setFont(uiFont);
        fullDetailsTextArea.setEditable(false); // Nicht editierbar
        fullDetailsTextArea.setWrapText(true); // Textumbruch
        // prefRowCount wird dynamisch sein müssen, um den gesamten Text anzuzeigen.
        // Für den Anfang können wir eine Mindestgröße festlegen.
        fullDetailsTextArea.setPrefRowCount(4); // Annahme: 4 Zeilen für den Status
        fullDetailsTextArea.setStyle("-fx-control-inner-background: " + toWebColor(Config.COLOR_HEADER_FOOTER) + ";" +
                "-fx-text-fill: " + toWebColor(textColor) + ";" +
                "-fx-background-color: transparent; -fx-border-width: 0;");
        // Kontextmenü und Selektierbarkeit sollten standardmäßig funktionieren,
        // da es ein TextArea ist und nicht bearbeitet werden kann.
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
            // NEU: Setze die Textfarbe für das TextArea über CSS-Style für den gesamten Text
            fullDetailsTextArea.setStyle("-fx-control-inner-background: " + toWebColor(Config.COLOR_HEADER_FOOTER) + ";" +
                    "-fx-text-fill: " + toWebColor(orgColor) + ";" +
                    "-fx-background-color: transparent; -fx-border-width: 0;");

            // Zeile 1: ID, Energie, IPs, DPs, DVs
            displayText.append(String.format("ID: %d %s | ER: %d | IP: %s | DP: %s | DV: %s\n",
                    selectedOrganism.getId(), selectedOrganism.isDead() ? "(DEAD)" : "",
                    selectedOrganism.getEr(), Arrays.toString(selectedOrganism.getIp()),
                    Arrays.toString(selectedOrganism.getDp()), Arrays.toString(selectedOrganism.getDv())));

            // Zeile 2: DRs
            List<Object> drs = selectedOrganism.getDrs();
            StringBuilder drsText = new StringBuilder("DRs: ");
            for(int i = 0; i < drs.size(); i++) {
                Object val = drs.get(i);
                String valStr = (val instanceof int[]) ? Arrays.toString((int[]) val) : (val != null ? val.toString() : "null");
                drsText.append(String.format("%d:[%s] ", i, valStr));
            }
            displayText.append(drsText.toString()).append("\n");

            // Zeile 3: Nächster Befehl und Argumente
            displayText.append("Next: ").append(logger.getNextInstructionInfo(selectedOrganism));

        } else {
            // Wenn kein Organismus ausgewählt ist, zeige leere oder Standard-Texte
            fullDetailsTextArea.setStyle("-fx-control-inner-background: " + toWebColor(Config.COLOR_HEADER_FOOTER) + ";" +
                    "-fx-text-fill: " + toWebColor(Config.COLOR_TEXT) + ";" +
                    "-fx-background-color: transparent; -fx-border-width: 0;");
            displayText.append("No Organism Selected.\nDRs: \nNext: ---");
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