package org.evochora.compiler.frontend.directive;

import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.ParsingContext;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;

/**
 * Das Basis-Interface für alle Direktiven-Handler.
 * Jeder Handler ist für die Verarbeitung einer bestimmten Direktive (z.B. ".DEFINE") zuständig.
 */
public interface IDirectiveHandler {

    /**
     * Gibt die Compiler-Phase an, in der dieser Handler aktiv sein soll.
     * @return Die Phase, in der der Handler ausgeführt wird.
     */
    CompilerPhase getPhase();

    /**
     * Parst die Direktive und ihre Argumente.
     *
     * @param context Der Kontext, der den Zugriff auf den Token-Stream und andere
     * für die aktuelle Phase relevante Informationen ermöglicht.
     * @return Ein entsprechender AST-Knoten für diese Direktive, oder {@code null},
     * wenn die Direktive keinen direkten Knoten im AST erzeugt (z.B. .DEFINE).
     */
    AstNode parse(ParsingContext context);

    /**
     * Parst eine Präprozessor-Direktive.
     * Diese Methode wird nur für Handler aufgerufen, die in der PREPROCESSING-Phase laufen.
     *
     * @param parsingContext Der Kontext für den Token-Stream.
     * @param preProcessorContext Der Kontext für Präprozessor-Definitionen (Makros, etc.).
     */
    default void parse(ParsingContext parsingContext, PreProcessorContext preProcessorContext) {
        // Standard-Implementierung, damit nicht jeder Handler diese Methode implementieren muss.
        parse(parsingContext);
    }
}