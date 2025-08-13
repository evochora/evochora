package org.evochora.compiler.core.directives;

import org.evochora.compiler.core.CompilerPhase;
import org.evochora.compiler.core.Parser;
import org.evochora.compiler.core.ParsingContext;
import org.evochora.compiler.core.ast.*;

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
