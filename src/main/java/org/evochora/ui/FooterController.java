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
        footerPane.setPrefHeight(Config.FOOTER_HEIGHT + 24);
        footerPane.setStyle("-fx-background-color: " + toWebColor(Config.COLOR_HEADER_FOOTER) + "; -fx-padding: 10;");
        footerPane.getChildren().addAll(fullDetailsTextArea);

        update(null, logger);
    }

    private void setupFullDetailsTextArea() {
        Font uiFont = Font.font("Monospaced", 12);
        fullDetailsTextArea.setFont(uiFont);
        fullDetailsTextArea.setEditable(false);
        fullDetailsTextArea.setWrapText(false);
        fullDetailsTextArea.setPrefRowCount(12);
        fullDetailsTextArea.setStyle("-fx-control-inner-background: " + toWebColor(Config.COLOR_HEADER_FOOTER) + ";" +
                "-fx-text-fill: " + toWebColor(Config.COLOR_TEXT) + ";" +
                "-fx-line-spacing: 15px;" +
                "-fx-background-color: transparent; -fx-border-width: 0;");
    }

    public void update(Organism selectedOrganism, Logger logger) {
        this.logger = logger;
        StringBuilder displayText = new StringBuilder();

        if (selectedOrganism != null) {
            Color orgColor = selectedOrganism.isDead() ? Config.COLOR_DEAD : Config.COLOR_TEXT;
            fullDetailsTextArea.setStyle("-fx-text-fill: " + toWebColor(orgColor) + "; -fx-control-inner-background: " + toWebColor(Config.COLOR_HEADER_FOOTER) + "; -fx-line-spacing: 2px;");

            displayText.append(String.format(Messages.get("footer.organismDetails"),
                    selectedOrganism.getId(), selectedOrganism.isDead() ? Messages.get("footer.organismStatus.dead") : "",
                    selectedOrganism.getEr(), Arrays.toString(selectedOrganism.getIp()),
                    Arrays.toString(selectedOrganism.getDp()), Arrays.toString(selectedOrganism.getDv())));

            // Einheitliche Labelbreite für DRs/FPRs/PRs berechnen
            String drsLabel = Messages.get("footer.label.drs");
            String prsLabel = Messages.get("footer.label.prs");
            String fprsLabel = "FPRs:";
            int labelWidth = Math.max(Math.max(drsLabel.length(), prsLabel.length()), fprsLabel.length());

            // DRs (spaltenbasiert)
            List<Object> drs = selectedOrganism.getDrs();
            displayText.append(formatRegisterRow(drsLabel, drs, labelWidth)).append("\n");

            // FPRs (spaltenbasiert, vor PRs anzeigen)
            List<Object> fprs = selectedOrganism.getFprs();
            displayText.append(formatRegisterRow(fprsLabel, fprs, labelWidth)).append("\n");

            // PRs (spaltenbasiert, nach FPRs)
            List<Object> prs = selectedOrganism.getPrs();
            displayText.append(formatRegisterRow(prsLabel, prs, labelWidth)).append("\n");

            // DS
            displayText.append(Messages.get("footer.label.stack")).append(logger.formatStack(selectedOrganism, true, 8)).append("\n");

            // RS
            displayText.append(Messages.get("footer.label.rs")).append(logger.formatReturnStack(selectedOrganism, true, 8)).append("\n");

            // Nächste Instruktion
            displayText.append(Messages.get("footer.label.nextInstruction")).append(logger.getNextInstructionInfo(selectedOrganism)).append("\n");

            String nextSourceInfo = getSourceInfoForNextInstruction(selectedOrganism);
            displayText.append(Messages.get("footer.label.line")).append(nextSourceInfo).append("\n");

        } else {
            fullDetailsTextArea.setStyle("-fx-text-fill: " + toWebColor(Config.COLOR_TEXT) + "; -fx-control-inner-background: " + toWebColor(Config.COLOR_HEADER_FOOTER) + "; -fx-line-spacing: 2px;");
            displayText.append("No Organism selected\nDRs:  ---\nFPRs: ---\nPRs:  ---\nDS: ---\nRS: ---\nNext: ---\nLine: ---");
        }
        fullDetailsTextArea.setText(displayText.toString());
    }

    private String getSourceInfoForNextInstruction(Organism organism) {
        String fullSourceInfo = logger.getSourceLocationString(organism, organism.getIp());
        if (fullSourceInfo.isEmpty()) return Messages.get("footer.notAvailable");

        String[] parts = fullSourceInfo.split(">", 2);
        if (parts.length > 1) {
            return parts[0].strip() + ">" + parts[1];
        }
        return fullSourceInfo.strip();
    }

    // Hilfsmethode: formatiert Registerzeilen (DRs, FPRs, PRs) mit fester Spaltenbreite
    private String formatRegisterRow(String label, List<Object> regs, int labelWidth) {
        // Indexbreite (z. B. 0..15 -> 2 Stellen)
        int indexWidth = Math.max(1, Integer.toString(Math.max(0, regs.size() - 1)).length());
        // Werte vorbereiten und maximale Breite ermitteln
        String[] values = new String[regs.size()];
        int valueWidth = 1;
        for (int i = 0; i < regs.size(); i++) {
            String v = this.logger.formatDrValue(regs.get(i));
            values[i] = v;
            valueWidth = Math.max(valueWidth, v.length());
        }
        // Label auf die gemeinsame Breite padden und genau ein Trennspace anhängen
        String paddedLabel = String.format("%-" + labelWidth + "s", label);
        StringBuilder sb = new StringBuilder(paddedLabel);
        sb.append(" ");
        // Zellen wie "ii=vvvv" mit Padding, 2 Spaces Abstand
        for (int i = 0; i < regs.size(); i++) {
            sb.append(String.format("%" + indexWidth + "d=%-" + valueWidth + "s", i, values[i]));
            if (i < regs.size() - 1) sb.append("  ");
        }
        return sb.toString();
    }

    public void updateLogger(Logger newLogger) { this.logger = newLogger; }
    public VBox getView() { return footerPane; }
    private String toWebColor(Color c) { return String.format("#%02X%02X%02X", (int)(c.getRed()*255), (int)(c.getGreen()*255), (int)(c.getBlue()*255)); }
}
