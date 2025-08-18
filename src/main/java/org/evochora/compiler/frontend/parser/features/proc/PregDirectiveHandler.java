package org.evochora.compiler.frontend.parser.features.proc;

import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.parser.ast.AstNode;

public class PregDirectiveHandler implements IDirectiveHandler {
    @Override public CompilerPhase getPhase() { return CompilerPhase.PARSING; }
    @Override public AstNode parse(ParsingContext context) {
        context.advance(); // .PREG konsumieren
        Token alias = context.consume(TokenType.REGISTER, "Expected a register alias (e.g. %TMP) after .PREG.");
        Token index = context.consume(TokenType.NUMBER, "Expected a procedure register index (0 or 1) after the alias.");

        // Das abschließende Newline wird von der Schleife im ProcDirectiveHandler behandelt.
        return new PregNode(alias, index);
    }
}
