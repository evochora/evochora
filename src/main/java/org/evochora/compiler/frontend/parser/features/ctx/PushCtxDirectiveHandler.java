package org.evochora.compiler.frontend.parser.features.ctx;

import org.evochora.compiler.frontend.parser.IParserDirectiveHandler;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.PushCtxNode;

public class PushCtxDirectiveHandler implements IParserDirectiveHandler {
    @Override
    public AstNode parse(ParsingContext context) {
        context.advance(); // consume .PUSH_CTX
        return new PushCtxNode();
    }
}
