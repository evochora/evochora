package org.evochora.compiler.features.ctx;

import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.model.ast.AstNode;

/**
 * Parser handler for the {@code .POP_CTX} directive (Phase 3). Consumes the
 * directive token and produces a {@link PopCtxNode}.
 */
public class PopCtxDirectiveHandler implements IParserStatementHandler {
    @Override
    public AstNode parse(ParsingContext context) {
        context.advance(); // consume .POP_CTX
        return new PopCtxNode();
    }
}
