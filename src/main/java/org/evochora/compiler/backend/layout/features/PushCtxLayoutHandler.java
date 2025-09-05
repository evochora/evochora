package org.evochora.compiler.backend.layout.features;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.backend.layout.ILayoutDirectiveHandler;
import org.evochora.compiler.backend.layout.LayoutContext;
import org.evochora.compiler.backend.layout.Nd;
import org.evochora.compiler.ir.IrDirective;

public class PushCtxLayoutHandler implements ILayoutDirectiveHandler {
    @Override
    public void handle(IrDirective directive, LayoutContext context) throws CompilationException {
        context.basePosStack().push(Nd.copy(context.basePos()));
        context.dvStack().push(Nd.copy(context.currentDv()));
        context.setBasePos(Nd.copy(context.currentPos()));
    }
}
