package org.evochora.compiler.core.directives;

import org.evochora.compiler.core.CompilerPhase;
import org.evochora.compiler.core.Parser;
import org.evochora.compiler.core.ast.AstNode;
import org.evochora.compiler.core.ast.DirNode;
import org.evochora.compiler.core.ast.VectorLiteralNode;

import org.evochora.compiler.core.ParsingContext;

public class DirDirectiveHandler implements IDirectiveHandler {
    @Override public CompilerPhase getPhase() { return CompilerPhase.PARSING; }
    @Override public AstNode parse(ParsingContext context) {
        context.advance(); // .DIR konsumieren
        Parser parser = (Parser) context; // Cast to access parser-specific methods
        AstNode vector = parser.expression();
        if (!(vector instanceof VectorLiteralNode)) {
            context.getDiagnostics().reportError("Expected a vector literal after .DIR.", "Unknown", context.peek().line());
        }
        return new DirNode(vector);
    }
}
