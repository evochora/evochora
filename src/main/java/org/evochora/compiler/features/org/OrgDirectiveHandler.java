package org.evochora.compiler.features.org;

import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.VectorLiteralNode;

/**
 * Handles the parsing of the <code>.ORG</code> directive.
 * This directive sets the origin (the starting position) for subsequent code.
 */
public class OrgDirectiveHandler implements IParserStatementHandler {

    /**
     * Parses an <code>.ORG</code> directive.
     * The syntax is <code>.ORG &lt;vector-literal&gt;</code>.
     * @param context The parsing context.
     * @return An {@link OrgNode} representing the directive.
     */
    @Override
    public AstNode parse(ParsingContext context) {
        context.advance(); // consume .ORG
        AstNode vector = context.expression();
        if (!(vector instanceof VectorLiteralNode)) {
            context.getDiagnostics().reportError("Expected a vector literal after .ORG.", context.peek().fileName(), context.peek().line());
        }
        return new OrgNode(vector);
    }
}
