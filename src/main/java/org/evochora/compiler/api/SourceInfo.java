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
 * @param expansion The number of the macro expansion the position stands in, 0 outside any.
 *                  Every expansion of a macro has the positions of the macro body, and a
 *                  condition in the body may decide differently in each; the number tells which
 *                  expansion an instruction was compiled in.
 */
public record SourceInfo(String fileName, int lineNumber, int columnNumber, String placement, int expansion) {

    /**
     * Returns the position without its expansion, for a table that describes the text of a
     * line rather than the code of one expansion: what a token is, a label, a register, a
     * procedure, is the same in every expansion of a macro body.
     *
     * @return This position with expansion 0.
     */
    public SourceInfo withoutExpansion() {
        return expansion == 0 ? this : new SourceInfo(fileName, lineNumber, columnNumber, placement, 0);
    }

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
