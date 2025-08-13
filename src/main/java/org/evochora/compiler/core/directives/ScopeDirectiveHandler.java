package org.evochora.compiler.core.directives;

import org.evochora.compiler.core.*;
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
    public AstNode parse(ParsingContext context) {
        context.advance(); // .SCOPE konsumieren

        Token scopeName = context.consume(TokenType.IDENTIFIER, "Expected scope name after .SCOPE.");
        context.consume(TokenType.NEWLINE, "Expected newline after scope name.");

        Parser parser = (Parser) context;
        List<AstNode> body = new ArrayList<>();
        while (!context.isAtEnd() && !(context.check(TokenType.DIRECTIVE) && context.peek().text().equalsIgnoreCase(".ENDS"))) {
            if (context.check(TokenType.NEWLINE)) {
                context.advance();
                continue;
            }
            body.add(parser.declaration());
        }

        context.consume(TokenType.DIRECTIVE, "Expected .ENDS directive to close scope block.");

        return new ScopeNode(scopeName, body.stream().filter(Objects::nonNull).toList());
    }
}
