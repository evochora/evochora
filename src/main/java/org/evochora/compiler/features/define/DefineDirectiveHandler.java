package org.evochora.compiler.features.define;

import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.model.ast.AstNode;

/**
 * Handler for the <code>.DEFINE</code> directive.
 * Parses a constant definition and creates a {@link DefineNode} in the AST.
 */
public class DefineDirectiveHandler implements IParserStatementHandler {

    /**
     * Parses a <code>.DEFINE</code> directive.
     * The syntax is <code>.DEFINE &lt;name&gt; &lt;value&gt;</code>.
     * @param context The parsing context.
     * @return A {@link DefineNode} representing the constant definition.
     */
    @Override
    public boolean supportsExport() { return true; }

    @Override
    public AstNode parse(ParsingContext context) {
        context.advance(); // consume .DEFINE

        Token name = context.consume(TokenType.IDENTIFIER, "Expected a constant name after .DEFINE.");
        boolean exported = context.isExported();
        AstNode valueNode = context.expression();

        if (name == null || valueNode == null) {
            return null;
        }

        return new DefineNode(name.text(), name.toSourceInfo(), valueNode, exported);
    }
}
