package org.evochora.compiler.frontend.parser.features.proc;

import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.parser.Parser; // NEU
import org.evochora.compiler.frontend.parser.ast.AstNode;

public class PregDirectiveHandler implements IDirectiveHandler {
    @Override public CompilerPhase getPhase() { return CompilerPhase.PARSING; }

    @Override public AstNode parse(ParsingContext context) {
        context.advance(); // .PREG konsumieren
        Token alias = context.consume(TokenType.REGISTER, "Expected a register alias (e.g. %TMP) after .PREG.");
        Token index = context.consume(TokenType.NUMBER, "Expected a procedure register index (0 or 1) after the alias.");

        // Erzeuge das Token für das echte Register (z.B. %PR0)
        String realRegName = "%PR" + index.value();
        Token realRegToken = new Token(TokenType.REGISTER, realRegName, null, alias.line(), alias.column(), alias.fileName());

        // Füge den Alias zum aktuellen Scope hinzu
        ((Parser) context).addRegisterAlias(alias.text(), realRegToken);

        // Erzeugt keinen AST-Knoten mehr
        return null;
    }
}