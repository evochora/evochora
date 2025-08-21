package org.evochora.compiler.frontend.parser.features.dir;

import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.VectorLiteralNode;

import org.evochora.compiler.frontend.parser.ParsingContext;

public class DirDirectiveHandler implements IDirectiveHandler {
    @Override public CompilerPhase getPhase() { return CompilerPhase.PARSING; }
    @Override public AstNode parse(ParsingContext context) {
        context.advance(); // .DIR konsumieren
        Parser parser = (Parser) context; // Cast to access parser-specific methods
        AstNode vector = parser.expression();
        if (!(vector instanceof VectorLiteralNode)) {
            context.getDiagnostics().reportError("Expected a vector literal after .DIR.", context.peek().fileName(), context.peek().line());
        }
        return new DirNode(vector);
    }
}
