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
        Token alias = parser.consume(TokenType.REGISTER, "Expected a register alias (e.g. %TMP) after .PREG.");
        Token index = parser.consume(TokenType.NUMBER, "Expected a procedure register index (0 or 1) after the alias.");
        // TODO: Semantische Analyse prüft, ob der Index 0 oder 1 ist.
        // Das abschließende Newline wird von der Schleife im ProcDirectiveHandler behandelt.
        return new PregNode(alias, index);
    }
}
