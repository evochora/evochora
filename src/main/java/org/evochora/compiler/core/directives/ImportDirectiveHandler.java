package org.evochora.compiler.core.directives;

import org.evochora.compiler.core.CompilerPhase;
import org.evochora.compiler.core.Parser;
import org.evochora.compiler.core.Token;
import org.evochora.compiler.core.TokenType;
import org.evochora.compiler.core.ast.AstNode;
import org.evochora.compiler.core.ast.ImportNode;

public class ImportDirectiveHandler implements IDirectiveHandler {
    @Override public CompilerPhase getPhase() { return CompilerPhase.PARSING; }
    @Override public AstNode parse(Parser parser) {
        parser.advance(); // .IMPORT konsumieren
        Token name = parser.consume(TokenType.IDENTIFIER, "Expected a name to import.");
        Token alias = null;
        if (parser.match(TokenType.IDENTIFIER) && parser.previous().text().equalsIgnoreCase("AS")) {
            alias = parser.consume(TokenType.IDENTIFIER, "Expected an alias name after 'AS'.");
        }
        return new ImportNode(name, alias);
    }
}
