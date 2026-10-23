package org.evochora.compiler.features.place;

import org.evochora.compiler.model.expression.Value;
import org.evochora.compiler.model.ast.LiteralNode;
import org.evochora.compiler.model.ast.OperandNode;
import org.evochora.compiler.model.ast.ExpressionNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.frontend.DirectiveLine;
import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.frontend.parser.IParsingContext;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.features.place.placement.*;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Handles the parsing of the <code>.PLACE</code> directive.
 * This directive is used to place a literal at a specific position in the world.
 */
public class PlaceDirectiveHandler implements IParserStatementHandler {

    /** The symbol between the bounds of a range. */
    private static final String RANGE = "..";

    /** The symbol of a wildcard, every cell along an axis. */
    private static final String WILDCARD = "*";

    /**
     * Parses a <code>.PLACE</code> directive.
     * The syntax is <code>.PLACE &lt;literal&gt; &lt;placement&gt; [,&lt;placement&gt;...]</code>.
     * The literal is a typed literal, or a name or a computation whose value the analysis checks
     * to be a typed value.
     * A placement is a {@code |}-list of components, each a number, a range {@code a..b}, a
     * stepped range {@code a:step:b} or the wildcard {@code *}, every number an operand; a
     * placement of one piece that is no range and no wildcard is one operand, which may give the
     * whole place, a vector or a position.
     * <p>
     * The literal is read first; the rest of the line is cut at the directive's own separators,
     * outside brackets: at {@code ,} into placements, at {@code |} into components, at {@code ..}
     * and {@code :} into the parts of a range. Each part is read as one operand; the analysis
     * checks the kinds of their values.
     * @param context The parsing context.
     * @return A {@link PlaceNode} representing the directive, or {@code null} after a reported
     *         error.
     */
    @Override
    public AstNode parse(IParsingContext context) {
        SourceInfo placeSourceInfo = context.peek().source();
        context.advance(); // consume .PLACE

        // 1. Parse the literal
        OperandNode literal = context.expression();
        boolean named = literal instanceof IdentifierNode || literal instanceof ExpressionNode;
        if (!named && !(literal instanceof LiteralNode written && written.value() instanceof Value.Typed)) {
            context.getDiagnostics().reportError("Expected a typed literal (e.g. DATA:5) for .PLACE.", context.peek().source().fileName(), context.peek().source().lineNumber());
            // The rest of the statement belongs to the directive and is reported with it, once
            context.skipStatement();
            return null;
        }

        // 2. Cut the rest of the line into placements, and each into its components
        List<Token> operands = new ArrayList<>();
        while (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
            operands.add(context.advance());
        }
        List<PlacementNode> placements = new ArrayList<>();
        boolean failed = false;
        for (List<Token> placement : DirectiveLine.pieces(operands, Set.of(TokenType.COMMA), Set.of())) {
            List<IPlacementComponent> components = new ArrayList<>();
            for (List<Token> piece : DirectiveLine.pieces(placement, Set.of(TokenType.PIPE), Set.of())) {
                Optional<IPlacementComponent> component = component(context, piece, placeSourceInfo);
                if (component.isEmpty()) {
                    failed = true;
                } else {
                    components.add(component.get());
                }
            }
            placements.add(new PlacementNode(components));
        }
        return failed ? null : new PlaceNode(literal, placements, placeSourceInfo);
    }

    /**
     * Reads one piece of a placement: the wildcard, a range, a stepped range or a single operand.
     *
     * @return The component, or empty after a reported error.
     */
    private Optional<IPlacementComponent> component(IParsingContext context, List<Token> piece, SourceInfo directive) {
        if (piece.size() == 1 && piece.getFirst().type() == TokenType.SYMBOL && piece.getFirst().text().equals(WILDCARD)) {
            return Optional.of(new WildcardValueComponent());
        }

        List<List<Token>> range = DirectiveLine.pieces(piece, Set.of(), Set.of(RANGE));
        if (range.size() > 1) {
            Optional<OperandNode> start = part(context, range.get(0), "Expected a number for the placement component.", directive);
            Optional<OperandNode> end = part(context, range.get(1), "Expected a number for the end of the range.", directive);
            if (range.size() > 2) {
                report(context, "Unexpected '" + RANGE + "'.", directive);
                return Optional.empty();
            }
            return start.isPresent() && end.isPresent()
                    ? Optional.of(new RangeValueComponent(start.get(), end.get())) : Optional.empty();
        }

        List<List<Token>> stepped = DirectiveLine.pieces(piece, Set.of(TokenType.COLON), Set.of());
        if (stepped.size() > 1) {
            Optional<OperandNode> start = part(context, stepped.get(0), "Expected a number for the placement component.", directive);
            Optional<OperandNode> step = part(context, stepped.get(1), "Expected a number for the step of the range.", directive);
            if (stepped.size() < 3) {
                report(context, "Expected ':' after the step value.", directive);
                return Optional.empty();
            }
            Optional<OperandNode> end = part(context, stepped.get(2), "Expected a number for the end of the stepped range.", directive);
            if (stepped.size() > 3) {
                report(context, "Unexpected ':'.", directive);
                return Optional.empty();
            }
            return start.isPresent() && step.isPresent() && end.isPresent()
                    ? Optional.of(new SteppedRangeValueComponent(start.get(), step.get(), end.get())) : Optional.empty();
        }

        return part(context, piece, "Expected a placement component (number, '*', range, etc.)", directive)
                .map(SingleValueComponent::new);
    }

    /**
     * Reads a part of a component as one operand; a part without tokens is reported here with the
     * given message, every other failure by the reader.
     */
    private static Optional<OperandNode> part(IParsingContext context, List<Token> tokens, String missing, SourceInfo directive) {
        if (tokens.isEmpty()) {
            report(context, missing, directive);
            return Optional.empty();
        }
        return context.readOperand(tokens);
    }

    private static void report(IParsingContext context, String message, SourceInfo at) {
        context.getDiagnostics().reportError(message, at.fileName(), at.lineNumber());
    }
}
