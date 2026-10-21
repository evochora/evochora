package org.evochora.compiler.features.dir;

import org.evochora.compiler.model.expression.Value;
import org.evochora.compiler.model.ast.LiteralNode;
import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.frontend.parser.IParsingContext;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

/**
 * Handles the parsing of the <code>.DIR</code> directive.
 * This directive sets the default direction for subsequent instructions.
 */
public class DirDirectiveHandler implements IParserStatementHandler {

    /**
     * Parses a <code>.DIR</code> directive.
     * The syntax is either <code>.DIR &lt;vector-literal&gt;</code>, which writes the direction
     * out, or <code>.DIR @+&lt;axis&gt;|&lt;axis&gt;</code>, which rotates the direction
     * currently in effect by 90 degrees in the plane those two axes span. The marker is the name
     * <code>@</code> followed by the symbol <code>+</code> or <code>-</code>.
     * @param context The parsing context.
     * @return A {@link DirNode} representing the directive, or {@code null} after a reported
     *         error, so that nothing but a vector or a rotation reaches the later phases.
     */
    @Override
    public AstNode parse(IParsingContext context) {
        Token directive = context.advance(); // consume .DIR

        if (context.check(TokenType.IDENTIFIER) && context.peek().text().equals("@")) {
            context.advance();
            boolean forward = context.matchSymbol("+");
            if (!forward && !context.matchSymbol("-")) {
                context.getDiagnostics().reportError("Expected '+' or '-' after '@' in .DIR.",
                        context.peek().source().fileName(), context.peek().source().lineNumber());
                return null;
            }
            Token axisA = context.consume(TokenType.NUMBER, "Expected the first axis of the rotation plane.");
            context.consume(TokenType.PIPE, "Expected '|' between the two axes of the rotation plane.");
            Token axisB = context.consume(TokenType.NUMBER, "Expected the second axis of the rotation plane.");
            return new DirNode(new DirNode.Mode.Rotation(forward, axis(axisA, context), axis(axisB, context)),
                    directive.source());
        }

        AstNode vector = context.expression();
        if (!(vector instanceof LiteralNode literal && literal.value() instanceof Value.Vector)) {
            context.getDiagnostics().reportError("Expected a vector literal or a rotation after .DIR.",
                    context.peek().source().fileName(), context.peek().source().lineNumber());
            return null;
        }
        return new DirNode(new DirNode.Mode.Absolute(literal), directive.source());
    }

    /**
     * Reads an axis number. The layout checks it against the world; here it only has to fit an
     * {@code int}, so that the layout can.
     */
    private static int axis(Token number, IParsingContext context) {
        long written = (Long) number.value();
        if (written < Integer.MIN_VALUE || written > Integer.MAX_VALUE) {
            context.getDiagnostics().reportError("Value " + written + " out of range for an axis.",
                    number.source().fileName(), number.source().lineNumber());
            return 0;
        }
        return (int) written;
    }
}
