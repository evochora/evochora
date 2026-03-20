package org.evochora.compiler.features.ctx;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.backend.layout.ILayoutDirectiveHandler;
import org.evochora.compiler.backend.layout.LayoutContext;
import org.evochora.compiler.backend.layout.Nd;
import org.evochora.compiler.model.ir.IrDirective;

/**
 * Layout handler for the {@code core:push_ctx} IR directive (Phase 9). Saves
 * the current base position and direction vector onto their respective stacks
 * and resets the base position to the current layout position.
 */
public class PushCtxLayoutHandler implements ILayoutDirectiveHandler {
    @Override
    public void handle(IrDirective directive, LayoutContext context) throws CompilationException {
        context.basePosStack().push(Nd.copy(context.basePos()));
        context.dvStack().push(Nd.copy(context.currentDv()));
        context.setBasePos(Nd.copy(context.currentPos()));
    }
}
