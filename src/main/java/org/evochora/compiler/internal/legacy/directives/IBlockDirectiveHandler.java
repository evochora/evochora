package org.evochora.compiler.internal.legacy.directives;

import org.evochora.compiler.internal.legacy.AnnotatedLine;
import org.evochora.compiler.internal.legacy.DefinitionExtractor;

/**
 * Interface für Handler, die mehrzeilige Definitionsblöcke verarbeiten
 * (z.B. .PROC ... .ENDP).
 */
public interface IBlockDirectiveHandler {
    /**
     * Wird aufgerufen, wenn der Handler die Kontrolle übernimmt (z.B. bei .PROC).
     * @param startLine Die Zeile, die den Block gestartet hat.
     */
    void startBlock(AnnotatedLine startLine);

    /**
     * Verarbeitet eine einzelne Zeile innerhalb des Blocks.
     * @param line Die zu verarbeitende Zeile.
     */
    void processLine(AnnotatedLine line);

    /**
     * Schließt den Block ab (z.B. bei .ENDP) und gibt die extrahierten
     * Definitionen und den verbleibenden Code an den Extractor zurück.
     * @param extractor Der aufrufende DefinitionExtractor.
     */
    void endBlock(DefinitionExtractor extractor);
}