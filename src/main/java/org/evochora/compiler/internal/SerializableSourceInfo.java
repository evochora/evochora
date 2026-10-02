package org.evochora.compiler.internal;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A serializable version of SourceInfo for use in LinearizedProgramArtifact.
 * Jackson writes it as the string produced by {@link #toString()}, which makes it usable as a
 * JSON object key; there is no way back, because a linearized artifact is never read in.
 * 
 * @param fileName The file where the code is located.
 * @param lineNumber The line number.
 * @param columnNumber The column number.
 * @param placement The alias chain of the module placement the position belongs to.
 * @param expansion The number of the macro expansion the position stands in, 0 outside any.
 */
public record SerializableSourceInfo(String fileName, int lineNumber, int columnNumber, String placement,
                                     int expansion) {
    
    /**
     * Creates a SerializableSourceInfo from a regular SourceInfo.
     *
     * @param sourceInfo The compiler-side source location to copy; must not be null.
     * @return A record holding the same file name, line number, column number, placement and
     *         expansion.
     */
    public static SerializableSourceInfo from(org.evochora.compiler.api.SourceInfo sourceInfo) {
        return new SerializableSourceInfo(
            sourceInfo.fileName(),
            sourceInfo.lineNumber(),
            sourceInfo.columnNumber(),
            sourceInfo.placement(),
            sourceInfo.expansion()
        );
    }
    
    /**
     * Serializes to a string format for use as a map key.
     * Format: "placement@fileName:lineNumber:columnNumber", or "fileName:lineNumber:columnNumber"
     * for the empty placement. An alias chain holds no {@code @}, so the first one ends it.
     * The expansion is not part of the key: the token map, which this key serves, describes a
     * position's text, and the debugger's annotations at runtime look a token up by its position
     * alone.
     */
    @JsonValue
    @Override
    public String toString() {
        String file = fileName != null ? fileName : "<unknown>";
        String placed = placement == null || placement.isEmpty() ? file : placement + "@" + file;
        return String.format("%s:%d:%d", placed, lineNumber, columnNumber);
    }

}
