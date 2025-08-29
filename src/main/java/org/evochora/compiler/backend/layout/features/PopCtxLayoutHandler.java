package org.evochora.compiler.backend.layout.features;

import org.evochora.compiler.backend.layout.ILayoutDirectiveHandler;
import org.evochora.compiler.backend.layout.LayoutContext;
import org.evochora.compiler.ir.IrDirective;

public class PopCtxLayoutHandler implements ILayoutDirectiveHandler {
    @Override
    public void handle(IrDirective directive, LayoutContext context) {
        if (!context.basePosStack().isEmpty()) {
            context.setBasePos(context.basePosStack().pop());
        }
        if (!context.dvStack().isEmpty()) {
            context.setCurrentDv(context.dvStack().pop());
        }
    }
}
