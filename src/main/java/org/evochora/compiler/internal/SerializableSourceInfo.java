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
 */
public record SerializableSourceInfo(String fileName, int lineNumber, int columnNumber) {
    
    /**
     * Creates a SerializableSourceInfo from a regular SourceInfo.
     *
     * @param sourceInfo The compiler-side source location to copy; must not be null.
     * @return A record holding the same file name, line number and column number.
     */
    public static SerializableSourceInfo from(org.evochora.compiler.api.SourceInfo sourceInfo) {
        return new SerializableSourceInfo(
            sourceInfo.fileName(),
            sourceInfo.lineNumber(),
            sourceInfo.columnNumber()
        );
    }
    
    /**
     * Serializes to a string format for use as a map key.
     * Format: "fileName:lineNumber:columnNumber"
     */
    @JsonValue
    @Override
    public String toString() {
        return String.format("%s:%d:%d", 
            fileName != null ? fileName : "<unknown>", 
            lineNumber, 
            columnNumber);
    }

}
