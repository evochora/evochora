package org.evochora.compiler.features.constdir;

import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.frontend.parser.IParsingContext;
import org.evochora.compiler.model.ast.AstNode;

/**
 * Handler for the <code>.CONST</code> directive.
 * Parses a constant definition and creates a {@link ConstNode} in the AST.
 */
public class ConstDirectiveHandler implements IParserStatementHandler {

    @Override
    public boolean supportsExport() { return true; }

    /**
     * Parses a <code>.CONST</code> directive.
     * The syntax is <code>.CONST &lt;name&gt; &lt;value&gt;</code>.
     * @param context The parsing context.
     * @return A {@link ConstNode} representing the constant definition.
     */
    @Override
    public AstNode parse(IParsingContext context) {
        context.advance(); // consume .CONST

        Token name = context.consume(TokenType.IDENTIFIER, "Expected a constant name after .CONST.");
        boolean exported = context.isExported();
        AstNode valueNode = context.expression();

        if (name == null || valueNode == null) {
            return null;
        }

        return new ConstNode(name.text(), name.source(), valueNode, exported);
    }
}
