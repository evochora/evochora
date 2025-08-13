package org.evochora.compiler.core.directives;

import org.evochora.compiler.core.phases.CompilerPhase;

/**
 * Das Basis-Interface für alle Direktiven-Handler.
 * Jeder Handler ist für die Verarbeitung einer bestimmten Direktive (z.B. ".DEFINE") zuständig.
 */
import org.evochora.compiler.core.phases.ParsingContext;
import org.evochora.compiler.core.ast.AstNode;

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
     *                für die aktuelle Phase relevante Informationen ermöglicht.
     * @return Ein entsprechender AST-Knoten für diese Direktive, oder {@code null},
     *         wenn die Direktive keinen direkten Knoten im AST erzeugt (z.B. .DEFINE).
     */
    AstNode parse(ParsingContext context);
}
