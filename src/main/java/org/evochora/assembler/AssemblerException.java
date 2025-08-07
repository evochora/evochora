package org.evochora.assembler;

public class AssemblerException extends RuntimeException {
    private final String programName;
    private final int lineNumber;
    private final String fileName; // NEU: Der Dateiname, in dem der Fehler auftrat.
    private final String message;
    private final String offendingLine;

    public AssemblerException(String programName, String fileName, int lineNumber, String message, String offendingLine) {
        // Die Standard-Nachricht wird für interne Logs beibehalten.
        super(String.format("Assembly Error in '%s' [%s:%d]: %s", programName, fileName, lineNumber, message));
        this.programName = programName;
        this.fileName = fileName;
        this.lineNumber = lineNumber;
        this.message = message;
        this.offendingLine = offendingLine;
    }

    @Override
    public String getMessage() {
        return message;
    }

    /**
     * NEU: Gibt eine vollständig formatierte, mehrzeilige Fehlermeldung zurück,
     * die für die Konsolenausgabe optimiert ist.
     */
    public String getFormattedMessage() {
        return String.format("FATALER FEHLER in '%s' (Zeile %d):\n%s\n> %s",
                this.fileName, this.lineNumber, this.message, this.offendingLine.strip());
    }
}