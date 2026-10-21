package org.evochora.compiler.features.constdir;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.backend.layout.ILayoutDirectiveHandler;
import org.evochora.compiler.backend.layout.LayoutContext;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;

/**
 * Layout handler for the {@code constdir:const_value} IR directive (Phase 9). A constant whose
 * value only the layout knows carries the tree of its value; the handler computes it where the
 * definition stands, with the cursor and the origin of that line, and records it in the layout
 * under the constant's qualified name, so that a line further down resolves the name to it. A
 * constant whose value is a text was resolved before the layout and is left to the emission.
 */
public final class ConstValueLayoutHandler implements ILayoutDirectiveHandler {

    @Override
    public void handle(IrDirective directive, LayoutContext context) throws CompilationException {
        if (directive.args().get("value") instanceof IrValue.Expression value
                && directive.args().get("name") instanceof IrValue.Str name) {
            context.record(name.value(), context.evaluate(value, directive.source()), directive.source());
        }
    }
}
