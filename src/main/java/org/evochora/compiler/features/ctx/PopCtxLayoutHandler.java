package org.evochora.compiler.features.ctx;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.backend.layout.ILayoutDirectiveHandler;
import org.evochora.compiler.backend.layout.LayoutContext;
import org.evochora.compiler.model.ir.IrDirective;

/**
 * Layout handler for the {@code core:pop_ctx} IR directive (Phase 9). Restores
 * the base position and direction vector from their respective stacks, reversing
 * the effect of a preceding {@link PushCtxLayoutHandler}.
 */
public class PopCtxLayoutHandler implements ILayoutDirectiveHandler {
    @Override
    public void handle(IrDirective directive, LayoutContext context) throws CompilationException {
        if (!context.basePosStack().isEmpty()) {
            context.setBasePos(context.basePosStack().pop());
        }
        if (!context.dvStack().isEmpty()) {
            context.setCurrentDv(context.dvStack().pop());
        }
    }
}
