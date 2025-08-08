package org.evochora.assembler;

import org.evochora.Messages;

/**
 * Custom exception for errors occurring during the assembly process.
 * It captures detailed information about the error, including file name and line number.
 */
public class AssemblerException extends RuntimeException {
    private final String programName;
    private final int lineNumber;
    private final String fileName; // NEW: The filename where the error occurred.
    private final String message;
    private final String offendingLine;

    public AssemblerException(String programName, String fileName, int lineNumber, String message, String offendingLine) {
        // The default message is kept for internal logs and is in English.
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
     * Returns a fully formatted, multi-line error message optimized for console output.
     * The message is retrieved from the resource bundle to support internationalization.
     *
     * @return The formatted, localized error message.
     */
    public String getFormattedMessage() {
        return Messages.get("assembler.exception.formattedMessage",
                this.fileName, this.lineNumber, this.message, this.offendingLine.strip());
    }
}