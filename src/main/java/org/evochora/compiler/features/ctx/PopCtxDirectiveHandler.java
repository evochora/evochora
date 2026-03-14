package org.evochora.compiler.features.ctx;

import org.evochora.compiler.frontend.parser.IParserDirectiveHandler;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.model.ast.AstNode;

public class PopCtxDirectiveHandler implements IParserDirectiveHandler {
    @Override
    public AstNode parse(ParsingContext context) {
        context.advance(); // consume .POP_CTX
        return new PopCtxNode();
    }
}
