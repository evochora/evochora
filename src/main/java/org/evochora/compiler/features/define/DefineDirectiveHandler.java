package org.evochora.compiler.features.define;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.frontend.parser.IParserDirectiveHandler;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.model.ast.AstNode;

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
        boolean exported = context.isExported();
        AstNode valueNode = context.expression();

        if (name == null || valueNode == null) {
            return null;
        }

        SourceInfo sourceInfo = new SourceInfo(name.fileName(), name.line(), name.column());
        return new DefineNode(name.text(), sourceInfo, valueNode, exported);
    }
}
