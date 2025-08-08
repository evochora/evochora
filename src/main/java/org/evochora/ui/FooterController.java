package org.evochora.ui;

import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import org.evochora.Config;
import org.evochora.Logger;
import org.evochora.Messages;
import org.evochora.organism.Organism;
import org.evochora.assembler.*;

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
        fullDetailsTextArea.setFont(uiFont);
        fullDetailsTextArea.setEditable(false);
        fullDetailsTextArea.setWrapText(false);
        fullDetailsTextArea.setPrefRowCount(5);
        fullDetailsTextArea.setStyle("-fx-control-inner-background: " + toWebColor(Config.COLOR_HEADER_FOOTER) + ";" +
                "-fx-text-fill: " + toWebColor(Config.COLOR_TEXT) + ";" +
                "-fx-background-color: transparent; -fx-border-width: 0;");
    }

    public void update(Organism selectedOrganism, Logger logger) {
        this.logger = logger;
        StringBuilder displayText = new StringBuilder();

        if (selectedOrganism != null) {
            Color orgColor = selectedOrganism.isDead() ? Config.COLOR_DEAD : Config.COLOR_TEXT;
            fullDetailsTextArea.setStyle("-fx-text-fill: " + toWebColor(orgColor) + "; -fx-control-inner-background: " + toWebColor(Config.COLOR_HEADER_FOOTER) + ";");

            displayText.append(String.format(Messages.get("footer.organismDetails"),
                    selectedOrganism.getId(), selectedOrganism.isDead() ? Messages.get("footer.organismStatus.dead") : "",
                    selectedOrganism.getEr(), Arrays.toString(selectedOrganism.getIp()),
                    Arrays.toString(selectedOrganism.getDp()), Arrays.toString(selectedOrganism.getDv())));

            List<Object> drs = selectedOrganism.getDrs();
            StringBuilder drsText = new StringBuilder(Messages.get("footer.label.drs"));
            for(int i = 0; i < drs.size(); i++) {
                drsText.append(String.format("%d=%s", i, this.logger.formatDrValue(drs.get(i))));
                if (i < drs.size() - 1) drsText.append(", ");
            }
            displayText.append(drsText.toString()).append("\n");

            displayText.append(Messages.get("footer.label.stack")).append(logger.formatStack(selectedOrganism, true, 8)).append("\n");

            displayText.append(Messages.get("footer.label.nextInstruction")).append(logger.getNextInstructionInfo(selectedOrganism)).append("\n");

            String nextSourceInfo = getSourceInfoForNextInstruction(selectedOrganism);
            displayText.append(Messages.get("footer.label.line")).append(nextSourceInfo);

        } else {
            fullDetailsTextArea.setStyle("-fx-text-fill: " + toWebColor(Config.COLOR_TEXT) + "; -fx-control-inner-background: " + toWebColor(Config.COLOR_HEADER_FOOTER) + ";");
            displayText.append(Messages.get("footer.noOrganismSelected"));
        }
        fullDetailsTextArea.setText(displayText.toString());
    }

    private String getSourceInfoForNextInstruction(Organism organism) {
        String fullSourceInfo = logger.getSourceLocationString(organism, organism.getIp());
        if (fullSourceInfo.isEmpty()) return Messages.get("footer.notAvailable");

        // Extract the part after the ">" for a clean display
        String[] parts = fullSourceInfo.split(">", 2);
        if (parts.length > 1) {
            return parts[0].strip() + ">" + parts[1];
        }
        return fullSourceInfo.strip();
    }

    public void updateLogger(Logger newLogger) { this.logger = newLogger; }
    public VBox getView() { return footerPane; }
    private String toWebColor(Color c) { return String.format("#%02X%02X%02X", (int)(c.getRed()*255), (int)(c.getGreen()*255), (int)(c.getBlue()*255)); }
}