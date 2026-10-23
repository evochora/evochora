package org.evochora.compiler.features.org;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.expression.INameResolution;
import org.evochora.compiler.frontend.DirectiveLine;
import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.frontend.parser.IParsingContext;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.OperandNode;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Handles the parsing of the <code>.ORG</code> directive.
 * This directive sets the origin (the starting position) for subsequent code.
 */
public class OrgDirectiveHandler implements IParserStatementHandler {

    /**
     * Parses an <code>.ORG</code> directive.
     * The syntax is <code>.ORG &lt;component&gt;{|&lt;component&gt;}</code>, where a component is
     * an operand whose value is a number, or such an operand preceded by the marker
     * <code>@+</code> or <code>@-</code> and thereby counted from the layout cursor rather than
     * from the origin of the enclosing module. The marker is the name <code>@</code> followed by
     * the symbol <code>+</code> or <code>-</code>, so a space between them is accepted; the
     * operand after it carries no sign of its own. A directive of one piece may give the whole
     * origin as one operand, a vector or a position.
     * <p>
     * The directive cuts its line at its own separator, the {@code |} outside brackets, and reads
     * each piece as one operand; the analysis checks the kinds of their values.
     * @param context The parsing context.
     * @return An {@link OrgNode} representing the directive, or {@code null} after a reported
     *         error.
     */
    @Override
    public AstNode parse(IParsingContext context) {
        Token directive = context.advance(); // consume .ORG
        List<Token> operands = new ArrayList<>();
        while (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
            operands.add(context.advance());
        }

        List<OperandNode> components = new ArrayList<>();
        List<Boolean> relative = new ArrayList<>();
        List<Boolean> backward = new ArrayList<>();
        boolean failed = false;
        for (List<Token> piece : DirectiveLine.pieces(operands, Set.of(TokenType.PIPE), Set.of())) {
            boolean marked = piece.size() >= 2 && isCursor(piece.get(0)) && isSign(piece.get(1));
            List<Token> rest = marked ? piece.subList(2, piece.size()) : piece;
            if (marked && !rest.isEmpty() && isSymbol(rest.getFirst(), "-")) {
                report(context, "A component marked with '@+' or '@-' carries no sign of its own.", rest.getFirst().source());
                failed = true;
                continue;
            }
            Optional<OperandNode> operand = read(context, rest, directive);
            if (operand.isEmpty()) {
                failed = true;
                continue;
            }
            components.add(operand.get());
            relative.add(marked);
            backward.add(marked && isSymbol(piece.get(1), "-"));
        }
        return failed ? null : new OrgNode(components, relative, backward, directive.source());
    }

    /**
     * Reads a piece as one operand; a piece without tokens is reported here, every other failure
     * by the reader.
     */
    private static Optional<OperandNode> read(IParsingContext context, List<Token> piece, Token directive) {
        if (piece.isEmpty()) {
            report(context, "Expected a number after .ORG.", directive.source());
            return Optional.empty();
        }
        return context.readOperand(piece);
    }


    private static boolean isCursor(Token token) {
        return token.type() == TokenType.IDENTIFIER && token.text().equals(INameResolution.CURSOR);
    }

    private static boolean isSign(Token token) {
        return isSymbol(token, "+") || isSymbol(token, "-");
    }

    private static boolean isSymbol(Token token, String text) {
        return token.type() == TokenType.SYMBOL && token.text().equals(text);
    }

    private static void report(IParsingContext context, String message, SourceInfo at) {
        context.getDiagnostics().reportError(message, at.fileName(), at.lineNumber());
    }
}
