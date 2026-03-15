package org.evochora.compiler.frontend.parser.features.place;

import org.evochora.compiler.frontend.parser.IParserDirectiveHandler;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.TypedLiteralNode;
import org.evochora.compiler.model.ast.VectorLiteralNode;
import org.evochora.compiler.frontend.parser.ast.placement.*;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles the parsing of the <code>.PLACE</code> directive.
 * This directive is used to place a literal at a specific position in the world.
 */
public class PlaceDirectiveHandler implements IParserDirectiveHandler {

    /**
     * Parses a <code>.PLACE</code> directive.
     * The syntax is <code>.PLACE &lt;literal&gt; &lt;placement_arg&gt; [,&lt;placement_arg&gt;...]</code>.
     * @param context The parsing context.
     * @return A {@link PlaceNode} representing the directive.
     */
    @Override
    public AstNode parse(ParsingContext context) {
        context.advance(); // consume .PLACE

        // 1. Parse the literal
        AstNode literal = context.expression();
        if (!(literal instanceof TypedLiteralNode)) {
            context.getDiagnostics().reportError("Expected a typed literal (e.g. DATA:5) for .PLACE.", context.peek().fileName(), context.peek().line());
        }

        // 2. Parse one or more placement arguments
        List<IPlacementArgumentNode> placements = new ArrayList<>();
        do {
            placements.add(parsePlacementArgument(context));
            if (context.peek().type() == TokenType.COMMA) {
                context.advance(); // consume comma
            } else {
                break;
            }
        } while (context.peek().type() != TokenType.NEWLINE && context.peek().type() != TokenType.END_OF_FILE);

        return new PlaceNode(literal, placements);
    }

    private IPlacementArgumentNode parsePlacementArgument(ParsingContext context) {
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
            List<Integer> values = new ArrayList<>();
            Token firstToken = null;
            for (IPlacementComponent comp : components) {
                SingleValueComponent single = (SingleValueComponent) comp;
                if (firstToken == null) firstToken = single.value();
                values.add((int) single.value().value());
            }
            org.evochora.compiler.api.SourceInfo sourceInfo = firstToken != null
                    ? new org.evochora.compiler.api.SourceInfo(firstToken.fileName(), firstToken.line(), firstToken.column())
                    : new org.evochora.compiler.api.SourceInfo("unknown", -1, -1);
            VectorLiteralNode vectorNode = new VectorLiteralNode(java.util.Collections.unmodifiableList(values), sourceInfo);
            return new VectorPlacementNode(vectorNode);
        }
    }

    private IPlacementComponent parseDimensionComponent(ParsingContext context) {
        if (context.peek().type() == TokenType.STAR) {
            Token starToken = context.advance(); // consume '*'
            return new WildcardValueComponent(starToken);
        }

        if (context.peek().type() == TokenType.NUMBER) {
            Token start = context.advance(); // consume start number

            if (context.peek().type() == TokenType.DOT_DOT) {
                context.advance(); // consume '..'
                Token end = context.consume(TokenType.NUMBER, "Expected a number for the end of the range.");
                return new RangeValueComponent(start, end);
            } else if (context.peek().type() == TokenType.COLON) {
                context.advance(); // consume ':'
                Token step = context.consume(TokenType.NUMBER, "Expected a number for the step of the range.");
                context.consume(TokenType.COLON, "Expected ':' after the step value.");
                Token end = context.consume(TokenType.NUMBER, "Expected a number for the end of the stepped range.");
                return new SteppedRangeValueComponent(start, step, end);
            } else {
                return new SingleValueComponent(start);
            }
        }

        context.getDiagnostics().reportError("Expected a placement component (number, '*', range, etc.)", context.peek().fileName(), context.peek().line());
        return new SingleValueComponent(context.peek()); // Dummy node
    }
}
