package org.evochora.compiler.frontend.parser.features.scope;

import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParsingContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Handler for the <code>.scope</code> and <code>.ends</code> directives.
 * Parses an entire scope block.
 */
public class ScopeDirectiveHandler implements IDirectiveHandler {
    @Override
    public CompilerPhase getPhase() {
        return CompilerPhase.PARSING;
    }

    /**
     * Parses a scope block, which starts with <code>.scope</code> and ends with <code>.ends</code>.
     * The syntax is <code>.scope &lt;name&gt; ... .ends</code>.
     * @param context The parsing context.
     * @return A {@link ScopeNode} representing the parsed scope.
     */
    @Override
    public AstNode parse(ParsingContext context) {
        context.advance(); // consume .SCOPE

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
