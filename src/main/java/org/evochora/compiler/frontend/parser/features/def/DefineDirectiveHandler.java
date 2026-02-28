package org.evochora.compiler.frontend.parser.features.def;

import org.evochora.compiler.frontend.parser.IParserDirectiveHandler;
import org.evochora.compiler.model.Token;
import org.evochora.compiler.model.TokenType;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.frontend.parser.ast.AstNode;

/**
 * Handler for the <code>.DEFINE</code> directive.
 * Parses a constant definition and creates a {@link DefineNode} in the AST.
 */
public class DefineDirectiveHandler implements IParserDirectiveHandler {

    /**
     * Parses a <code>.DEFINE</code> directive.
     * The syntax is <code>.DEFINE &lt;name&gt; &lt;value&gt;</code>.
     * @param context The parsing context.
     * @return A {@link DefineNode} representing the constant definition.
     */
    @Override
    public AstNode parse(ParsingContext context) {
        context.advance(); // consume .DEFINE

        Token name = context.consume(TokenType.IDENTIFIER, "Expected a constant name after .DEFINE.");
        AstNode valueNode = ((Parser) context).expression();

        if (name == null || valueNode == null) {
            return null;
        }

        return new DefineNode(name, valueNode);
    }
}
