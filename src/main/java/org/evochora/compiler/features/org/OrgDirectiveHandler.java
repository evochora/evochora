package org.evochora.compiler.features.org;

import org.evochora.compiler.model.expression.Value;
import org.evochora.compiler.model.ast.LiteralNode;
import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.frontend.parser.IParsingContext;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles the parsing of the <code>.ORG</code> directive.
 * This directive sets the origin (the starting position) for subsequent code.
 */
public class OrgDirectiveHandler implements IParserStatementHandler {

    /**
     * Parses an <code>.ORG</code> directive.
     * The syntax is <code>.ORG &lt;component&gt;{|&lt;component&gt;}</code>, where a component is
     * a number, which a <code>-</code> may precede, or a number preceded by the marker
     * <code>@+</code> or <code>@-</code> and thereby counted from the layout cursor rather than
     * from the origin of the enclosing module. The marker is the name <code>@</code> followed by
     * the symbol <code>+</code> or <code>-</code>, so a space between them is accepted.
     * @param context The parsing context.
     * @return An {@link OrgNode} representing the directive, or {@code null} after a reported
     *         error.
     */
    @Override
    public AstNode parse(IParsingContext context) {
        Token directive = context.advance(); // consume .ORG
        Token start = context.peek();
        List<Long> values = new ArrayList<>();
        List<Boolean> relative = new ArrayList<>();
        do {
            boolean marked = context.check(TokenType.IDENTIFIER) && context.peek().text().equals("@");
            int sign = 1;
            if (marked) {
                context.advance();
                if (context.matchSymbol("-")) {
                    sign = -1;
                } else if (!context.matchSymbol("+")) {
                    context.getDiagnostics().reportError("Expected '+' or '-' after '@' in .ORG.",
                            context.peek().source().fileName(), context.peek().source().lineNumber());
                    return null;
                }
            }
            Token minus = context.matchSymbol("-") ? context.previous() : null;
            Token number = context.consume(TokenType.NUMBER, "Expected a number after .ORG.");
            long value = (Long) number.value();
            if (minus != null) {
                if (marked) {
                    context.getDiagnostics().reportError(
                            "A component marked with '@+' or '@-' carries no sign of its own.",
                            minus.source().fileName(), minus.source().lineNumber());
                }
                value = -value;
            }
            values.add(sign * value);
            relative.add(marked);
        } while (context.match(TokenType.PIPE));

        return new OrgNode(new LiteralNode(new Value.Vector(values), start.source()),
                List.copyOf(relative), directive.source());
    }
}
