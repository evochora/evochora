package org.evochora.compiler.features.dir;

import org.evochora.compiler.expression.INameResolution;
import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.frontend.parser.IParsingContext;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.OperandNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Handles the parsing of the <code>.DIR</code> directive.
 * This directive sets the default direction for subsequent instructions.
 */
public class DirDirectiveHandler implements IParserStatementHandler {

    /**
     * Parses a <code>.DIR</code> directive.
     * The syntax is either <code>.DIR &lt;vector&gt;</code>, which gives the direction as one
     * operand whose value is a vector, written out, a name or a computation, or
     * <code>.DIR @+&lt;axis&gt;|&lt;axis&gt;</code>, which rotates the direction currently in
     * effect by 90 degrees in the plane those two axes span. The marker is the name
     * <code>@</code> followed by the symbol <code>+</code> or <code>-</code>; the axes are numbers.
     * @param context The parsing context.
     * @return A {@link DirNode} representing the directive, or {@code null} after a reported
     *         error, so that nothing but an operand or a rotation reaches the later phases; the
     *         analysis checks that the operand's value is a vector.
     */
    @Override
    public AstNode parse(IParsingContext context) {
        Token directive = context.advance(); // consume .DIR

        List<Token> operands = new ArrayList<>();
        while (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
            operands.add(context.advance());
        }
        if (operands.size() >= 2 && operands.get(0).type() == TokenType.IDENTIFIER
                && operands.get(0).text().equals(INameResolution.CURSOR) && isSign(operands.get(1))) {
            return rotation(context, directive, operands);
        }
        if (operands.isEmpty()) {
            reportNeither(context, directive);
            return null;
        }
        Optional<OperandNode> vector = context.readOperand(operands);
        if (vector.isEmpty()) {
            return null;
        }
        if (vector.get() instanceof RegisterNode) {
            reportNeither(context, directive);
            return null;
        }
        return new DirNode(new DirNode.Mode.Absolute(vector.get()), directive.source());
    }

    /**
     * Reads a rotation: the marker, the first axis, {@code |} and the second axis, each axis a
     * number, and nothing after them.
     */
    private static AstNode rotation(IParsingContext context, Token directive, List<Token> operands) {
        boolean forward = operands.get(1).text().equals("+");
        Token axisA = expect(context, operands, 2, TokenType.NUMBER, "Expected the first axis of the rotation plane.", directive);
        Token pipe = axisA == null ? null
                : expect(context, operands, 3, TokenType.PIPE, "Expected '|' between the two axes of the rotation plane.", directive);
        Token axisB = pipe == null ? null
                : expect(context, operands, 4, TokenType.NUMBER, "Expected the second axis of the rotation plane.", directive);
        if (axisB == null) {
            return null;
        }
        if (operands.size() > 5) {
            Token extra = operands.get(5);
            context.getDiagnostics().reportError("Unexpected '" + extra.text() + "'.",
                    extra.source().fileName(), extra.source().lineNumber());
            return null;
        }
        return new DirNode(new DirNode.Mode.Rotation(forward, axis(axisA, context), axis(axisB, context)),
                directive.source());
    }

    /**
     * Takes the token at an index if it is of the expected type, or reports the error at it, or at
     * the directive where the line ends before it.
     */
    private static Token expect(IParsingContext context, List<Token> operands, int index, TokenType type,
                                String message, Token directive) {
        if (index < operands.size() && operands.get(index).type() == type) {
            return operands.get(index);
        }
        Token at = index < operands.size() ? operands.get(index) : directive;
        context.getDiagnostics().reportError(message, at.source().fileName(), at.source().lineNumber());
        return null;
    }

    private static boolean isSign(Token token) {
        return token.type() == TokenType.SYMBOL && (token.text().equals("+") || token.text().equals("-"));
    }

    private static void reportNeither(IParsingContext context, Token directive) {
        context.getDiagnostics().reportError("Expected a vector literal or a rotation after .DIR.",
                directive.source().fileName(), directive.source().lineNumber());
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
