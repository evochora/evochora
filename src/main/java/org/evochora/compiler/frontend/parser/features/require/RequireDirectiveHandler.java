package org.evochora.compiler.frontend.parser.features.require;

import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.parser.ParsingContext;

public class RequireDirectiveHandler implements IDirectiveHandler {
    @Override public CompilerPhase getPhase() { return CompilerPhase.PARSING; }
    @Override public AstNode parse(ParsingContext context) {
        context.advance(); // .REQUIRE konsumieren
        Token path = context.consume(TokenType.STRING, "Expected a file path in quotes after .REQUIRE.");
        Token alias = null;
        if (context.match(TokenType.IDENTIFIER) && context.previous().text().equalsIgnoreCase("AS")) {
            alias = context.consume(TokenType.IDENTIFIER, "Expected an alias name after 'AS'.");
        }
        return new RequireNode(path, alias);
    }
}
