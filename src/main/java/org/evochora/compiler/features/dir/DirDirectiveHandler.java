package org.evochora.compiler.features.dir;

import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.frontend.parser.IParsingContext;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.VectorLiteralNode;
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
     * currently in effect by 90 degrees in the plane those two axes span.
     * @param context The parsing context.
     * @return A {@link DirNode} representing the directive, or {@code null} after a reported
     *         error, so that nothing but a vector or a rotation reaches the later phases.
     */
    @Override
    public AstNode parse(IParsingContext context) {
        Token directive = context.advance(); // consume .DIR

        boolean forward = context.check(TokenType.AT_PLUS);
        if (forward || context.check(TokenType.AT_MINUS)) {
            context.advance();
            Token axisA = context.consume(TokenType.NUMBER, "Expected the first axis of the rotation plane.");
            context.consume(TokenType.PIPE, "Expected '|' between the two axes of the rotation plane.");
            Token axisB = context.consume(TokenType.NUMBER, "Expected the second axis of the rotation plane.");
            return new DirNode(new DirNode.Mode.Rotation(forward, (int) axisA.value(), (int) axisB.value()),
                    directive.toSourceInfo());
        }

        AstNode vector = context.expression();
        if (!(vector instanceof VectorLiteralNode literal)) {
            context.getDiagnostics().reportError("Expected a vector literal or a rotation after .DIR.",
                    context.peek().fileName(), context.peek().line());
            return null;
        }
        return new DirNode(new DirNode.Mode.Absolute(literal), directive.toSourceInfo());
    }
}
