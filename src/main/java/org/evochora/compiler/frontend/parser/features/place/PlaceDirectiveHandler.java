package org.evochora.compiler.frontend.parser.features.place;

import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.TypedLiteralNode;
import org.evochora.compiler.frontend.parser.ast.VectorLiteralNode;
import org.evochora.compiler.frontend.parser.ast.placement.*;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles the parsing of the <code>.place</code> directive.
 * This directive is used to place a literal at a specific position in the world.
 */
public class PlaceDirectiveHandler implements IDirectiveHandler {
    @Override public CompilerPhase getPhase() { return CompilerPhase.PARSING; }

    /**
     * Parses a <code>.place</code> directive.
     * The syntax is <code>.place &lt;literal&gt; &lt;placement_arg&gt; [,&lt;placement_arg&gt;...]</code>.
     * @param context The parsing context.
     * @return A {@link PlaceNode} representing the directive.
     */
    @Override public AstNode parse(ParsingContext context) {
        context.advance(); // consume .PLACE
        Parser parser = (Parser) context;

        // 1. Parse the literal
        AstNode literal = parser.expression();
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
            List<Token> valueTokens = new ArrayList<>();
            for (IPlacementComponent comp : components) {
                SingleValueComponent single = (SingleValueComponent) comp;
                valueTokens.add(single.value());
            }
            VectorLiteralNode vectorNode = new VectorLiteralNode(valueTokens);
            return new VectorPlacementNode(vectorNode);
        }
    }

    private IPlacementComponent parseDimensionComponent(ParsingContext context) {
        Parser parser = (Parser) context;
        if (context.peek().type() == TokenType.STAR) {
            Token starToken = context.advance(); // consume '*'
            return new WildcardValueComponent(starToken);
        }

        if (context.peek().type() == TokenType.NUMBER) {
            Token start = context.advance(); // consume start number

            if (context.peek().type() == TokenType.DOT_DOT) {
                context.advance(); // consume '..'
                Token end = parser.consume(TokenType.NUMBER, "Expected a number for the end of the range.");
                return new RangeValueComponent(start, end);
            } else if (context.peek().type() == TokenType.COLON) {
                context.advance(); // consume ':'
                Token step = parser.consume(TokenType.NUMBER, "Expected a number for the step of the range.");
                parser.consume(TokenType.COLON, "Expected ':' after the step value.");
                Token end = parser.consume(TokenType.NUMBER, "Expected a number for the end of the stepped range.");
                return new SteppedRangeValueComponent(start, step, end);
            } else {
                return new SingleValueComponent(start);
            }
        }

        context.getDiagnostics().reportError("Expected a placement component (number, '*', range, etc.)", context.peek().fileName(), context.peek().line());
        return new SingleValueComponent(context.peek()); // Dummy node
    }
}
