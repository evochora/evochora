package org.evochora.compiler.diagnostics;

/**
 * Represents a single diagnostic message (error, warning, info)
 * that occurs during the compilation process.
 *
 * @param type The type of the diagnostic (e.g., ERROR, WARNING).
 * @param message The diagnostic message.
 * @param fileName The name of the file where the issue occurred.
 * @param lineNumber The line number of the issue.
 */
public record Diagnostic(
        Type type,
        String message,
        String fileName,
        int lineNumber
) {
    /**
     * The type of a diagnostic message.
     */
    public enum Type {
        /** An error that prevents compilation. */
        ERROR,
        /** A warning that does not prevent compilation. */
        WARNING,
        /** An informational message. */
        INFO
    }

    @Override
    public String toString() {
        return String.format("[%s] %s:%d: %s", type, fileName, lineNumber, message);
    }
}
