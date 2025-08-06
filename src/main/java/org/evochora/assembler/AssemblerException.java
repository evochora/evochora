// src/main/java/org/evochora/assembler/AssemblerException.java
package org.evochora.assembler;

public class AssemblerException extends RuntimeException {
    private final String programName;
    private final int lineNumber;
    private final String message;
    private final String offendingLine; // NEU: Feld für die fehlerhafte Zeile

    // NEU: Konstruktor wurde um den Parameter 'offendingLine' erweitert.
    public AssemblerException(String programName, int lineNumber, String message, String offendingLine) {
        super(String.format("Assembly Error in '%s' (Line %d): %s", programName, lineNumber, message));
        this.programName = programName;
        this.lineNumber = lineNumber;
        this.message = message;
        this.offendingLine = offendingLine;
    }

    // Overload für den alten Konstruktor, falls er noch irgendwo verwendet wird (gute Praxis)
    public AssemblerException(String programName, int lineNumber, String message) {
        this(programName, lineNumber, message, "");
    }


    public String getProgramName() {
        return programName;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    @Override
    public String getMessage() {
        return message;
    }

    /**
     * NEU: Gibt eine vollständig formatierte Fehlermeldung inklusive der fehlerhaften Codezeile zurück.
     */
    public String getFormattedMessage() {
        if (offendingLine != null && !offendingLine.isBlank()) {
            return String.format("Assembly Error in '%s' (Line %d): %s\n> %s", programName, lineNumber, message, offendingLine.strip());
        } else {
            return String.format("Assembly Error in '%s' (Line %d): %s", programName, lineNumber, message);
        }
    }
}