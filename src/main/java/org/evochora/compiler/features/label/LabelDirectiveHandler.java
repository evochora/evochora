package org.evochora.compiler.features.label;

import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

/**
 * Parses the {@code .LABEL} directive produced by the preprocessor's label rewriting.
 * The syntax is {@code .LABEL NAME [statement]}, where the optional statement is
 * the code on the same line as the label (e.g., {@code .LABEL L1 NOP}).
 */
public class LabelDirectiveHandler implements IParserStatementHandler {

    @Override
    public boolean supportsExport() { return true; }

    @Override
    public AstNode parse(ParsingContext context) {
        context.advance(); // consume .LABEL
        Token nameToken = context.consume(TokenType.IDENTIFIER, "Expected label name after .LABEL.");
        boolean exported = context.isExported();
        AstNode statement = context.declaration();
        return new LabelNode(nameToken.text(), nameToken.toSourceInfo(), statement, exported);
    }
}
