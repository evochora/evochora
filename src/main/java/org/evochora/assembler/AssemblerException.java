// src/main/java/org/evochora/assembler/AssemblerException.java
package org.evochora.assembler;

public class AssemblerException extends RuntimeException {
    private final String programName;
    private final int lineNumber;
    private final String message;

    public AssemblerException(String programName, int lineNumber, String message) {
        super(String.format("Assembly Error in '%s' (Line %d): %s", programName, lineNumber, message));
        this.programName = programName;
        this.lineNumber = lineNumber;
        this.message = message;
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

    public String getFormattedMessage() {
        return String.format("Assembly Error in '%s' (Line %d): %s", programName, lineNumber, message);
    }
}