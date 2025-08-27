package org.evochora.compiler.frontend.parser.features.def;

import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.frontend.parser.ast.AstNode;

/**
 * Handler for the <code>.define</code> directive.
 * Parses a constant definition and creates a {@link DefineNode} in the AST.
 */
public class DefineDirectiveHandler implements IDirectiveHandler {

    @Override
    public CompilerPhase getPhase() {
        return CompilerPhase.PARSING;
    }

    /**
     * Parses a <code>.define</code> directive.
     * The syntax is <code>.define &lt;name&gt; &lt;value&gt;</code>.
     * @param context The parsing context.
     * @return A {@link DefineNode} representing the constant definition.
     */
    @Override
    public AstNode parse(ParsingContext context) {
        context.advance(); // consume .DEFINE

        Token name = context.consume(TokenType.IDENTIFIER, "Expected a constant name after .DEFINE.");
        AstNode valueNode = ((Parser) context).expression();

        if (name == null || valueNode == null) {
            // An error occurred while parsing the arguments, which has already been reported.
            return null;
        }

        return new DefineNode(name, valueNode);
    }
}