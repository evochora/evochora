package org.evochora.compiler.features.label;

import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.frontend.parser.IParsingContext;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

/**
 * Parses the {@code .LABEL} directive produced by the preprocessor's label rewriting.
 * The syntax is {@code .LABEL NAME}. A label names the position of the statement that follows
 * it, which the enclosing loop parses as the next statement (e.g., the {@code NOP} in
 * {@code .LABEL L1 NOP}); a label may stand alone on its line.
 */
public class LabelDirectiveHandler implements IParserStatementHandler {

    @Override
    public boolean supportsExport() { return true; }

    @Override
    public AstNode parse(IParsingContext context) {
        context.advance(); // consume .LABEL
        Token nameToken = context.consume(TokenType.IDENTIFIER, "Expected label name after .LABEL.");
        boolean exported = context.isExported();
        return new LabelNode(nameToken.text(), nameToken.source(), exported);
    }
}
