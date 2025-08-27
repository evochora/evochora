package org.evochora.compiler.frontend.parser.features.dir;

import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.VectorLiteralNode;

import org.evochora.compiler.frontend.parser.ParsingContext;

/**
 * Handles the parsing of the <code>.dir</code> directive.
 * This directive sets the default direction for subsequent instructions.
 */
public class DirDirectiveHandler implements IDirectiveHandler {
    @Override public CompilerPhase getPhase() { return CompilerPhase.PARSING; }

    /**
     * Parses a <code>.dir</code> directive.
     * The syntax is <code>.dir &lt;vector-literal&gt;</code>.
     * @param context The parsing context.
     * @return A {@link DirNode} representing the directive.
     */
    @Override public AstNode parse(ParsingContext context) {
        context.advance(); // consume .DIR
        Parser parser = (Parser) context; // Cast to access parser-specific methods
        AstNode vector = parser.expression();
        if (!(vector instanceof VectorLiteralNode)) {
            context.getDiagnostics().reportError("Expected a vector literal after .DIR.", context.peek().fileName(), context.peek().line());
        }
        return new DirNode(vector);
    }
}
