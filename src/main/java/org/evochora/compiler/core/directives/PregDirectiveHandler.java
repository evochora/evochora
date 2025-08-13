package org.evochora.compiler.core.directives;

import org.evochora.compiler.core.CompilerPhase;
import org.evochora.compiler.core.Parser;
import org.evochora.compiler.core.Token;
import org.evochora.compiler.core.TokenType;
import org.evochora.compiler.core.ast.AstNode;
import org.evochora.compiler.core.ast.PregNode;

public class PregDirectiveHandler implements IDirectiveHandler {
    @Override public CompilerPhase getPhase() { return CompilerPhase.PARSING; }
    @Override public AstNode parse(Parser parser) {
        parser.advance(); // .PREG konsumieren
        Token alias = parser.consume(TokenType.IDENTIFIER, "Expected an alias name after .PREG.");
        Token register = parser.consume(TokenType.REGISTER, "Expected a procedure register (%PR0 or %PR1) after the alias.");
        // TODO: Semantische Analyse prüft, ob es wirklich %PR0 oder %PR1 ist.
        return new PregNode(alias, register);
    }
}
