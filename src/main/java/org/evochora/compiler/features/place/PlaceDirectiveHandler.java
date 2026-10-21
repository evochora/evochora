package org.evochora.compiler.features.place;

import org.evochora.compiler.model.expression.Value;
import org.evochora.compiler.model.ast.LiteralNode;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.frontend.parser.IParsingContext;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.features.place.placement.*;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles the parsing of the <code>.PLACE</code> directive.
 * This directive is used to place a literal at a specific position in the world.
 */
public class PlaceDirectiveHandler implements IParserStatementHandler {

    /**
     * Parses a <code>.PLACE</code> directive.
     * The syntax is <code>.PLACE &lt;literal&gt; &lt;placement_arg&gt; [,&lt;placement_arg&gt;...]</code>.
     * @param context The parsing context.
     * @return A {@link PlaceNode} representing the directive.
     */
    @Override
    public AstNode parse(IParsingContext context) {
        SourceInfo placeSourceInfo = context.peek().source();
        context.advance(); // consume .PLACE

        // 1. Parse the literal
        AstNode literal = context.expression();
        if (!(literal instanceof LiteralNode typed && typed.value() instanceof Value.Typed)) {
            context.getDiagnostics().reportError("Expected a typed literal (e.g. DATA:5) for .PLACE.", context.peek().source().fileName(), context.peek().source().lineNumber());
            // The rest of the statement belongs to the directive and is reported with it, once
            context.skipStatement();
            return null;
        }

        // 2. Parse one or more placement arguments
        List<IPlacementArgumentNode> placements = new ArrayList<>();
        do {
            placements.add(parsePlacementArgument(context));
            if (!context.match(TokenType.COMMA)) {
                break;
            }
        } while (context.peek().type() != TokenType.NEWLINE && context.peek().type() != TokenType.END_OF_FILE);

        return new PlaceNode(typed, placements, placeSourceInfo);
    }

    private IPlacementArgumentNode parsePlacementArgument(IParsingContext context) {
        SourceInfo sourceInfo = context.peek().source();

        List<IPlacementComponent> components = new ArrayList<>();
        boolean isRangeExpression = false;

        do {
            IPlacementComponent component = parseDimensionComponent(context);
            components.add(component);

            if (!(component instanceof SingleValueComponent)) {
                isRangeExpression = true;
            }

            if (context.peek().type() == TokenType.PIPE) {
                context.advance(); // consume '|'
            } else {
                break;
            }
        } while (true);

        if (isRangeExpression) {
            List<List<IPlacementComponent>> dimensions = new ArrayList<>();
            for (IPlacementComponent comp : components) {
                dimensions.add(List.of(comp));
            }
            return new RangeExpressionNode(dimensions);
        } else {
            List<Long> values = new ArrayList<>();
            for (IPlacementComponent comp : components) {
                SingleValueComponent single = (SingleValueComponent) comp;
                values.add((long) single.value());
            }
            return new VectorPlacementNode(new LiteralNode(new Value.Vector(values), sourceInfo));
        }
    }

    private IPlacementComponent parseDimensionComponent(IParsingContext context) {
        if (context.matchSymbol("*")) {
            return new WildcardValueComponent();
        }

        if (context.check(TokenType.NUMBER) || context.checkSymbol("-")) {
            int start = coordinate(context, "Expected a number for the placement component.");

            if (context.matchSymbol("..")) {
                int end = coordinate(context, "Expected a number for the end of the range.");
                return new RangeValueComponent(start, end);
            } else if (context.peek().type() == TokenType.COLON) {
                context.advance(); // consume ':'
                int step = coordinate(context, "Expected a number for the step of the range.");
                context.consume(TokenType.COLON, "Expected ':' after the step value.");
                int end = coordinate(context, "Expected a number for the end of the stepped range.");
                return new SteppedRangeValueComponent(start, step, end);
            } else {
                return new SingleValueComponent(start);
            }
        }

        context.getDiagnostics().reportError("Expected a placement component (number, '*', range, etc.)", context.peek().source().fileName(), context.peek().source().lineNumber());
        return new SingleValueComponent(0);
    }

    /**
     * Reads a number of a placement as a coordinate, negated when a {@code -} precedes it. The
     * layout checks it against the world; here it only has to fit an {@code int}, so that the
     * layout can.
     */
    private static int coordinate(IParsingContext context, String errorMessage) {
        boolean negative = context.matchSymbol("-");
        Token number = context.consume(TokenType.NUMBER, errorMessage);
        long written = negative ? -(Long) number.value() : (Long) number.value();
        if (written < Integer.MIN_VALUE || written > Integer.MAX_VALUE) {
            context.getDiagnostics().reportError("Value " + written + " out of range for a coordinate.",
                    number.source().fileName(), number.source().lineNumber());
            return 0;
        }
        return (int) written;
    }
}
