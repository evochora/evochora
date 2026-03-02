package org.evochora.compiler.frontend.parser.features.dir;

import org.evochora.compiler.frontend.parser.IParserDirectiveHandler;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.VectorLiteralNode;

/**
 * Handles the parsing of the <code>.DIR</code> directive.
 * This directive sets the default direction for subsequent instructions.
 */
public class DirDirectiveHandler implements IParserDirectiveHandler {

    /**
     * Parses a <code>.DIR</code> directive.
     * The syntax is <code>.DIR &lt;vector-literal&gt;</code>.
     * @param context The parsing context.
     * @return A {@link DirNode} representing the directive.
     */
    @Override
    public AstNode parse(ParsingContext context) {
        context.advance(); // consume .DIR
        AstNode vector = context.expression();
        if (!(vector instanceof VectorLiteralNode)) {
            context.getDiagnostics().reportError("Expected a vector literal after .DIR.", context.peek().fileName(), context.peek().line());
        }
        return new DirNode(vector);
    }
}
