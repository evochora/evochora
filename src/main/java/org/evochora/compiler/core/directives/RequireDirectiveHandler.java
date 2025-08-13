package org.evochora.compiler.core.directives;

import org.evochora.compiler.core.*;
import org.evochora.compiler.core.ast.AstNode;
import org.evochora.compiler.core.ast.RequireNode;
import org.evochora.compiler.core.phases.CompilerPhase;
import org.evochora.compiler.core.phases.ParsingContext;

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
