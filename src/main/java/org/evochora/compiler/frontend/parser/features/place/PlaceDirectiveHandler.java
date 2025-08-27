package org.evochora.compiler.frontend.parser.features.place;

import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.TypedLiteralNode;
import org.evochora.compiler.frontend.parser.ast.VectorLiteralNode;

/**
 * Handles the parsing of the <code>.place</code> directive.
 * This directive is used to place a literal at a specific position in the world.
 */
public class PlaceDirectiveHandler implements IDirectiveHandler {
    @Override public CompilerPhase getPhase() { return CompilerPhase.PARSING; }

    /**
     * Parses a <code>.place</code> directive.
     * The syntax is <code>.place &lt;typed-literal&gt; &lt;vector-literal&gt;</code>.
     * @param context The parsing context.
     * @return A {@link PlaceNode} representing the directive.
     */
    @Override public AstNode parse(ParsingContext context) {
        context.advance(); // consume .PLACE
        Parser parser = (Parser) context;
        AstNode literal = parser.expression();
        if (!(literal instanceof TypedLiteralNode)) {
             context.getDiagnostics().reportError("Expected a typed literal (e.g. DATA:5) for .PLACE.", context.peek().fileName(), context.peek().line());
        }
        AstNode position = parser.expression();
        if (!(position instanceof VectorLiteralNode)) {
             context.getDiagnostics().reportError("Expected a vector literal for the position in .PLACE.", context.peek().fileName(), context.peek().line());
        }
        return new PlaceNode(literal, position);
    }
}
