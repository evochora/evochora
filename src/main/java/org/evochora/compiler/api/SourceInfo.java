package org.evochora.compiler.api;

/**
 * A pure data class representing a position in the source code.
 * It is part of the public compiler API and free of implementation details.
 *
 * @param fileName The file where the code is located.
 * @param lineNumber The line number.
 * @param columnNumber The column number.
 * @param placement The alias chain of the module placement the position belongs to. Two
 *                  placements of one file share its positions but not its code, so a position
 *                  is only complete with its placement. The chain of the main module for the
 *                  main file and the files it sources; usually empty.
 */
public record SourceInfo(String fileName, int lineNumber, int columnNumber, String placement) {

    private static final String UNKNOWN_FILE = "<unknown>";

    /**
     * Names a position the way every message of the compiler does: {@code file:line}.
     *
     * @param src The position, or {@code null} for an item that has none.
     * @return {@code file:line}; the file falls back to {@code <unknown>}, the line to 0.
     */
    public static String position(SourceInfo src) {
        if (src == null) {
            return UNKNOWN_FILE + ":0";
        }
        return (src.fileName() != null ? src.fileName() : UNKNOWN_FILE) + ":" + src.lineNumber();
    }

    /**
     * Prefixes a message with the position it concerns, the way every message of the compiler
     * is prefixed: {@code file:line: message}.
     *
     * @param src     The position, or {@code null} for an item that has none; then the message
     *                is returned as it is.
     * @param message The message.
     * @return The prefixed message.
     */
    public static String locate(SourceInfo src, String message) {
        return src == null ? message : position(src) + ": " + message;
    }
}
