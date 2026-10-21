package org.evochora.compiler.features.importdir;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.backend.layout.ILayoutDirectiveHandler;
import org.evochora.compiler.backend.layout.LayoutContext;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;

/**
 * Layout handler for the {@code importdir:bound_value} IR directive (Phase 9). The directive
 * stands at the import line and carries a value bound by {@code USING VALUE} that only the
 * layout computes; the handler computes it there, with the importer's cursor and origin, and
 * records it in the layout under the qualified name of the requirement it binds. The import's
 * directive stands before the imported module's items, so the module's lines find the value.
 */
public final class BoundValueLayoutHandler implements ILayoutDirectiveHandler {

    @Override
    public void handle(IrDirective directive, LayoutContext context) throws CompilationException {
        IrValue.Str name = (IrValue.Str) directive.args().get("name");
        context.record(name.value(), context.evaluate(directive.args().get("value"), directive.source()), directive.source());
    }
}
