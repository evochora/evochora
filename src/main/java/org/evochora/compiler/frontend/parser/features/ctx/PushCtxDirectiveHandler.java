package org.evochora.compiler.frontend.parser.features.ctx;

import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.PushCtxNode;

public class PushCtxDirectiveHandler implements IDirectiveHandler {
    @Override
    public CompilerPhase getPhase() {
        return CompilerPhase.PARSING;
    }

    @Override
    public AstNode parse(ParsingContext context) {
        context.advance(); // consume .PUSH_CTX
        return new PushCtxNode();
    }
}
