package org.evochora.compiler.core.directives;

import org.evochora.compiler.core.CompilerPhase;
import org.evochora.compiler.core.Parser;
import org.evochora.compiler.core.Token;
import org.evochora.compiler.core.TokenType;
import org.evochora.compiler.core.ast.AstNode;
import org.evochora.compiler.core.ast.ExportNode;

public class ExportDirectiveHandler implements IDirectiveHandler {
    @Override public CompilerPhase getPhase() { return CompilerPhase.PARSING; }
    @Override public AstNode parse(Parser parser) {
        parser.advance(); // .EXPORT konsumieren
        Token name = parser.consume(TokenType.IDENTIFIER, "Expected a name to export.");
        return new ExportNode(name);
    }
}
