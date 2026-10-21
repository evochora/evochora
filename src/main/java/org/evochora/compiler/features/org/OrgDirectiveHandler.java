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
     * a number, or a number preceded by <code>@+</code> or <code>@-</code> and thereby counted
     * from the layout cursor rather than from the origin of the enclosing module.
     * @param context The parsing context.
     * @return An {@link OrgNode} representing the directive.
     */
    @Override
    public AstNode parse(IParsingContext context) {
        Token directive = context.advance(); // consume .ORG
        Token start = context.peek();
        List<Long> values = new ArrayList<>();
        List<Boolean> relative = new ArrayList<>();
        do {
            boolean marked = true;
            int sign;
            if (context.matchSymbol("@+")) {
                sign = 1;
            } else if (context.matchSymbol("@-")) {
                sign = -1;
            } else {
                marked = false;
                sign = 1;
            }
            Token number = context.consume(TokenType.NUMBER, "Expected a number after .ORG.");
            long value = (Long) number.value();
            if (marked && value < 0) {
                context.getDiagnostics().reportError(
                        "A component marked with '@+' or '@-' carries no sign of its own.",
                        number.source().fileName(), number.source().lineNumber());
            }
            values.add(sign * value);
            relative.add(marked);
        } while (context.match(TokenType.PIPE));

        return new OrgNode(new LiteralNode(new Value.Vector(values), start.source()),
                List.copyOf(relative), directive.source());
    }
}
