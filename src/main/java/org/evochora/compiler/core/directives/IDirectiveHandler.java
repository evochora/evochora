package org.evochora.compiler.core.directives;

import org.evochora.compiler.core.CompilerPhase;

/**
 * Das Basis-Interface für alle Direktiven-Handler.
 * Jeder Handler ist für die Verarbeitung einer bestimmten Direktive (z.B. ".DEFINE") zuständig.
 */
import org.evochora.compiler.core.Parser;
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
     * @param parser Der Parser, der den Handler aufruft. Dies gibt dem Handler Zugriff
     *               auf den Token-Stream, um Argumente zu konsumieren.
     * @return Ein entsprechender AST-Knoten für diese Direktive, oder {@code null},
     *         wenn die Direktive keinen direkten Knoten im AST erzeugt (z.B. .DEFINE).
     */
    AstNode parse(Parser parser);
}
