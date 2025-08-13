package org.evochora.compiler.core.directives;

import org.evochora.compiler.core.CompilerPhase;
import org.evochora.compiler.core.Parser;
import org.evochora.compiler.core.Token;
import org.evochora.compiler.core.TokenType;
import org.evochora.compiler.core.ast.AstNode;
import org.evochora.compiler.core.ast.ScopeNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Handler für die .SCOPE- und .ENDS-Direktiven.
 * Parst einen gesamten Geltungsbereichs-Block.
 */
public class ScopeDirectiveHandler implements IDirectiveHandler {
    @Override
    public CompilerPhase getPhase() {
        return CompilerPhase.PARSING;
    }

    @Override
    public AstNode parse(Parser parser) {
        parser.advance(); // .SCOPE konsumieren

        Token scopeName = parser.consume(TokenType.IDENTIFIER, "Expected scope name after .SCOPE.");
        parser.consume(TokenType.NEWLINE, "Expected newline after scope name.");

        List<AstNode> body = new ArrayList<>();
        while (!parser.isAtEnd() && !(parser.check(TokenType.DIRECTIVE) && parser.peek().text().equalsIgnoreCase(".ENDS"))) {
            body.add(parser.declaration());
        }

        parser.consume(TokenType.DIRECTIVE, "Expected .ENDS directive to close scope block.");

        return new ScopeNode(scopeName, body.stream().filter(Objects::nonNull).toList());
    }
}
