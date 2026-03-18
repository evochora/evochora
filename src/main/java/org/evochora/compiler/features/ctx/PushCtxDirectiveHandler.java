package org.evochora.compiler.features.ctx;

import org.evochora.compiler.frontend.module.PlacementContext;
import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.model.ast.AstNode;

public class PushCtxDirectiveHandler implements IParserStatementHandler {
    @Override
    public AstNode parse(ParsingContext context) {
        Object value = context.peek().value();
        context.advance(); // consume .PUSH_CTX

        if (value instanceof PlacementContext pc) {
            return new PushCtxNode(pc.sourcePath(), pc.aliasChain());
        }
        // Backward compatibility: plain String value from legacy callers
        String targetPath = (value instanceof String s) ? s : null;
        return new PushCtxNode(targetPath);
    }
}
