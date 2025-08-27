package org.evochora.compiler.frontend.parser.features.org;

import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.VectorLiteralNode;

/**
 * Handles the parsing of the <code>.org</code> directive.
 * This directive sets the origin (the starting position) for subsequent code.
 */
public class OrgDirectiveHandler implements IDirectiveHandler {
    @Override public CompilerPhase getPhase() { return CompilerPhase.PARSING; }

    /**
     * Parses an <code>.org</code> directive.
     * The syntax is <code>.org &lt;vector-literal&gt;</code>.
     * @param context The parsing context.
     * @return An {@link OrgNode} representing the directive.
     */
    @Override public AstNode parse(ParsingContext context) {
        context.advance(); // consume .ORG
        Parser parser = (Parser) context;
        AstNode vector = parser.expression();
        if (!(vector instanceof VectorLiteralNode)) {
            context.getDiagnostics().reportError("Expected a vector literal after .ORG.", context.peek().fileName(), context.peek().line());
        }
        return new OrgNode(vector);
    }
}
