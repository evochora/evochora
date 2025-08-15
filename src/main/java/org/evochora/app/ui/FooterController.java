package org.evochora.app.ui;

import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import org.evochora.runtime.Simulation;
import org.evochora.app.i18n.LocalizationProvider;
import org.evochora.runtime.Config;
import org.evochora.compiler.internal.legacy.AssemblyProgram;
import org.evochora.compiler.internal.legacy.DefinitionExtractor;
import org.evochora.compiler.internal.legacy.ProgramMetadata;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Organism;

import java.util.Arrays;
import java.util.List;

public class FooterController {

    private final VBox footerPane;
    private final TextArea fullDetailsTextArea;
    private Simulation simulation; // Hinzugefügt, um den Welt-Kontext für planTick zu haben
    private org.evochora.runtime.VirtualMachine vm;

    public FooterController() {
        this.fullDetailsTextArea = new TextArea();
        setupFullDetailsTextArea();

        footerPane = new VBox(0);
        footerPane.setPrefHeight(Config.FOOTER_HEIGHT + 24);
        footerPane.setStyle("-fx-background-color: " + toWebColor(Config.COLOR_HEADER_FOOTER) + "; -fx-padding: 10;");
        footerPane.getChildren().addAll(fullDetailsTextArea);

        update(null, null);
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

    public void update(Simulation simulation, Organism selectedOrganism) {
        this.simulation = simulation;
        this.vm = (simulation != null) ? simulation.getVirtualMachine() : null; // NEU

        StringBuilder displayText = new StringBuilder();

        if (selectedOrganism != null) {
            Color orgColor = selectedOrganism.isDead() ? Config.COLOR_DEAD : Config.COLOR_TEXT;
            fullDetailsTextArea.setStyle("-fx-text-fill: " + toWebColor(orgColor) + "; -fx-control-inner-background: " + toWebColor(Config.COLOR_HEADER_FOOTER) + "; -fx-line-spacing: 2px;");

            displayText.append(String.format(LocalizationProvider.getString("footer.organismDetails"),
                    selectedOrganism.getId(), selectedOrganism.isDead() ? LocalizationProvider.getString("footer.organismStatus.dead") : "",
                    selectedOrganism.getEr(), Arrays.toString(selectedOrganism.getIp()),
                    Arrays.toString(selectedOrganism.getDp()), Arrays.toString(selectedOrganism.getDv())));

            // Einheitliche Labelbreite für DRs/FPRs/PRs berechnen
            String drsLabel = LocalizationProvider.getString("footer.label.drs");
            String prsLabel = LocalizationProvider.getString("footer.label.prs");
            String fprsLabel = "FPRs:";
            int labelWidth = Math.max(Math.max(drsLabel.length(), prsLabel.length()), fprsLabel.length());

            // DRs (spaltenbasiert)
            List<Object> drs = selectedOrganism.getDrs();
            displayText.append(formatRegisterRow(drsLabel, drs, labelWidth)).append("\n");

            // FPRs (spaltenbasiert, vor PRs anzeigen)
            displayText.append(formatFprRow(selectedOrganism, fprsLabel, labelWidth)).append("\n");

            // PRs (spaltenbasiert, nach FPRs)
            List<Object> prs = selectedOrganism.getPrs();
            displayText.append(formatRegisterRow(prsLabel, prs, labelWidth)).append("\n");

            // DS
            displayText.append(LocalizationProvider.getString("footer.label.stack")).append(formatStack(selectedOrganism, true, 8)).append("\n");

            // CS
            displayText.append(String.format("%-" + labelWidth + "s", "CS:")).append(formatCallStack(selectedOrganism)).append("\n");

            // Nächste Instruktion
            displayText.append(LocalizationProvider.getString("footer.label.nextInstruction")).append(getNextInstructionInfo(selectedOrganism)).append("\n");

            String nextSourceInfo = getSourceInfoForNextInstruction(selectedOrganism);
            displayText.append(LocalizationProvider.getString("footer.label.line")).append(nextSourceInfo).append("\n");

        } else {
            fullDetailsTextArea.setStyle("-fx-text-fill: " + toWebColor(Config.COLOR_TEXT) + "; -fx-control-inner-background: " + toWebColor(Config.COLOR_HEADER_FOOTER) + "; -fx-line-spacing: 2px;");
            displayText.append("No Organism selected\nDRs:  ---\nFPRs: ---\nPRs:  ---\nDS: ---\nCS: ---\nNext: ---\nLine: ---");
        }
        fullDetailsTextArea.setText(displayText.toString());
    }

    // --- NEUE PRIVATE HILFSMETHODEN (ersetzen die alte Logger-Funktionalität) ---

    private String formatStack(Organism org, boolean multiline, int max) {
        if (org == null || org.getDataStack().isEmpty()) {
            return "[]";
        }
        List<String> items = org.getDataStack().stream()
                .map(this::formatObject)
                .limit(max)
                .collect(java.util.stream.Collectors.toList());

        if (multiline && !items.isEmpty()) {
            return "\n" + String.join("\n", items);
        }
        return "[" + String.join(", ", items) + "]";
    }

    private String formatCallStack(Organism org) {
        if (org == null || org.getCallStack().isEmpty()) {
            return "[]";
        }
        return org.getCallStack().stream()
                .map(frame -> frame.procName)
                .collect(java.util.stream.Collectors.joining(" -> "));
    }

    private String getNextInstructionInfo(Organism org) {
        if (org == null || org.isDead() || this.vm == null) {
            return "N/A";
        }
        try {
            // KORREKTUR: Wir verwenden die VirtualMachine, um die nächste Instruktion zu planen.
            Instruction nextInstruction = this.vm.plan(org);
            return nextInstruction.getName();
        } catch (Exception e) {
            return "ERROR";
        }
    }

    private String formatObject(Object obj) {
        if (obj instanceof int[]) {
            return Arrays.toString((int[]) obj);
        }
        return obj != null ? obj.toString() : "null";
    }

    private String getSourceInfoForNextInstruction(Organism organism) {
        if (organism == null) {
            return "N/A";
        }
        // TODO: In einer späteren Phase wird dies durch das Lesen der SourceMap aus dem ProgramArtifact ersetzt.
        // String sourceLine = simulation.getSourceMapFor(organism).getLine(organism.getIp());
        return "N/A";
    }

    private String formatFprRow(Organism organism, String label, int labelWidth) {
        final String paddedLabel = String.format("%-" + labelWidth + "s", label);
        final String emptyResult = paddedLabel + " ---";

        if (organism.getCallStack().isEmpty()) {
            return emptyResult;
        }

        // 1. Metadaten und obersten Frame abrufen
        String programId = AssemblyProgram.getProgramIdForOrganism(organism);
        if (programId == null) return emptyResult;
        ProgramMetadata metadata = AssemblyProgram.getMetadataForProgram(programId);
        if (metadata == null) return emptyResult;

        Organism.ProcFrame topFrame = organism.getCallStack().peek();
        if (topFrame == null) return emptyResult;

        // 2. Prozedurnamen direkt aus dem Frame holen
        String procName = topFrame.procName;
        if (procName == null || procName.equals("UNKNOWN")) return emptyResult;

        // 3. Formale Parameternamen holen
        DefinitionExtractor.ProcMeta procMeta = metadata.procMetaMap().get(procName);
        if (procMeta == null || procMeta.formalParams().isEmpty()) return emptyResult;
        List<String> paramNames = procMeta.formalParams();

        // 4. Werte und Bindungen abrufen und formatieren
        StringBuilder sb = new StringBuilder(paddedLabel);
        sb.append(" ");
        for (int i = 0; i < paramNames.size(); i++) {
            String name = paramNames.get(i).toUpperCase();
            Object value = organism.getFpr(i);
            Integer boundRegId = topFrame.fprBindings.get(Instruction.FPR_BASE + i);

            String boundName = "??";
            if (boundRegId != null) {
                if (boundRegId < Instruction.PR_BASE) {
                    boundName = "DR" + boundRegId;
                } else if (boundRegId < Instruction.FPR_BASE) {
                    boundName = "PR" + (boundRegId - Instruction.PR_BASE);
                } else {
                    // Für verschachtelte Aufrufe ist die Auflösung des Namens zu komplex für die UI.
                    // Wir zeigen den FPR-Index des Aufrufers an.
                    boundName = "FPR" + (boundRegId - Instruction.FPR_BASE);
                }
            }

            sb.append(String.format("%s[%s]=%s", name, boundName, formatObject(value)));
            if (i < paramNames.size() - 1) sb.append("  ");
        }
        return sb.toString();
    }


    // Hilfsmethode: formatiert Registerzeilen (DRs, FPRs, PRs) mit fester Spaltenbreite
    private String formatRegisterRow(String label, List<Object> regs, int labelWidth) {
        // Indexbreite (z. B. 0..15 -> 2 Stellen)
        int indexWidth = Math.max(1, Integer.toString(Math.max(0, regs.size() - 1)).length());
        // Werte vorbereiten und maximale Breite ermitteln
        String[] values = new String[regs.size()];
        int valueWidth = 1;
        for (int i = 0; i < regs.size(); i++) {
            String v = this.formatObject(regs.get(i));
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

    public VBox getView() { return footerPane; }
    private String toWebColor(Color c) { return String.format("#%02X%02X%02X", (int)(c.getRed()*255), (int)(c.getGreen()*255), (int)(c.getBlue()*255)); }
}
