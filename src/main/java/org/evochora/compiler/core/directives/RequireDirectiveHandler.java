package org.evochora.compiler.core.directives;

import org.evochora.compiler.core.CompilerPhase;
import org.evochora.compiler.core.Parser;
import org.evochora.compiler.core.Token;
import org.evochora.compiler.core.TokenType;
import org.evochora.compiler.core.ast.AstNode;
import org.evochora.compiler.core.ast.RequireNode;

public class RequireDirectiveHandler implements IDirectiveHandler {
    @Override public CompilerPhase getPhase() { return CompilerPhase.PARSING; }
    @Override public AstNode parse(Parser parser) {
        parser.advance(); // .REQUIRE konsumieren
        Token name = parser.consume(TokenType.IDENTIFIER, "Expected a symbol name to require.");
        return new RequireNode(name);
    }
}
