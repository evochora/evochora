package org.evochora.compiler.model.ir;

import java.util.List;
import java.util.Objects;

/**
 * Linear IR program container. The order of items is the emission order
 * as produced by the frontend and should be preserved by backends.
 *
 * @param programName Name of the program.
 * @param items Sequential list of IR items.
 * @param debugInfo The text of the program's files and the classification of its tokens, which
 *                  every phase that builds a new program carries over unchanged.
 */
public record IrProgram(String programName, List<IrItem> items, DebugInfo debugInfo) {

    /**
     * Rejects a program without debug information; one without files carries
     * {@link DebugInfo#none()}.
     */
    public IrProgram {
        Objects.requireNonNull(debugInfo, "debugInfo");
    }
}
