package org.evochora.compiler.frontend.parser.features.place;

import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.ParsingContext;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.TypedLiteralNode;
import org.evochora.compiler.frontend.parser.ast.VectorLiteralNode;

public class PlaceDirectiveHandler implements IDirectiveHandler {
    @Override public CompilerPhase getPhase() { return CompilerPhase.PARSING; }
    @Override public AstNode parse(ParsingContext context) {
        context.advance(); // .PLACE konsumieren
        Parser parser = (Parser) context;
        AstNode literal = parser.expression();
        if (!(literal instanceof TypedLiteralNode)) {
             context.getDiagnostics().reportError("Expected a typed literal (e.g. DATA:5) for .PLACE.", "Unknown", context.peek().line());
        }
        AstNode position = parser.expression();
        if (!(position instanceof VectorLiteralNode)) {
             context.getDiagnostics().reportError("Expected a vector literal for the position in .PLACE.", "Unknown", context.peek().line());
        }
        return new PlaceNode(literal, position);
    }
}
