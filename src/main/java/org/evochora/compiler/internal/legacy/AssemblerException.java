package org.evochora.compiler.internal.legacy;

/**
 * Custom exception for errors occurring during the assembly process.
 * It captures detailed information about the error, including file name and line number.
 */
public class AssemblerException extends RuntimeException {

    public final String programName;
    public final String fileName;
    public final int lineNumber;
    public final String lineContent;

    public AssemblerException(String programName, String fileName, int lineNumber, String message, String lineContent) {
        super(message);
        this.programName = programName;
        this.fileName = fileName;
        this.lineNumber = lineNumber;
        this.lineContent = lineContent;
    }
}