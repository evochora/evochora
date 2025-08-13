package org.evochora.compiler.core.directives;

import org.evochora.compiler.core.CompilerPhase;
import org.evochora.compiler.core.Parser;
import org.evochora.compiler.core.ast.AstNode;
import org.evochora.compiler.core.ast.DirNode;
import org.evochora.compiler.core.ast.VectorLiteralNode;

public class DirDirectiveHandler implements IDirectiveHandler {
    @Override public CompilerPhase getPhase() { return CompilerPhase.PARSING; }
    @Override public AstNode parse(Parser parser) {
        parser.advance(); // .DIR konsumieren
        AstNode vector = parser.expression();
        if (!(vector instanceof VectorLiteralNode)) {
            parser.getDiagnostics().reportError("Expected a vector literal after .DIR.", "Unknown", parser.peek().line());
        }
        return new DirNode(vector);
    }
}
