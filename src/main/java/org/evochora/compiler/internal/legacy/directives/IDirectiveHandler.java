package org.evochora.compiler.internal.legacy.directives;

import org.evochora.compiler.internal.legacy.AnnotatedLine;
import org.evochora.compiler.internal.legacy.PassManagerContext;

/**
 * Interface für alle Direktiven-Handler im ersten Assembler-Durchlauf.
 */
@FunctionalInterface
public interface IDirectiveHandler {
    /**
     * Verarbeitet eine Direktive.
     * @param line Die kommentierte Zeile, die die Direktive enthält.
     * @param context Der aktuelle Zustand des Assemblers (enthält alle Maps etc.).
     */
    void handle(AnnotatedLine line, PassManagerContext context);
}