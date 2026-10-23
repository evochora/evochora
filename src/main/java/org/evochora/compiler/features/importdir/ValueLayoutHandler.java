package org.evochora.compiler.features.importdir;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.backend.layout.ILayoutDirectiveHandler;
import org.evochora.compiler.backend.layout.LayoutContext;
import org.evochora.compiler.model.expression.NameExpression;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;

/**
 * Layout handler for the {@code importdir:value} IR directive (Phase 9), at the line of a value
 * requirement. A value only the layout computes is either the requirement's own qualified name,
 * when the import line bound it to a computation, or the tree of a default or a configured value.
 * The own name records nothing: the import line's {@code bound_value} directive, which stands
 * before the module's items, has recorded the value under that name already. A tree is computed
 * here, with the module's cursor and origin, and recorded under the requirement's qualified name.
 * A value that is a text was resolved before the layout and is left to the emission.
 */
public final class ValueLayoutHandler implements ILayoutDirectiveHandler {

    @Override
    public void handle(IrDirective directive, LayoutContext context) throws CompilationException {
        if (!(directive.args().get("value") instanceof IrValue.Expression value)
                || !(directive.args().get("name") instanceof IrValue.Str name)) {
            return;
        }
        if (value.expression() instanceof NameExpression self && self.name().equals(name.value())) {
            return;
        }
        context.record(name.value(), context.evaluate(value, directive.source()), directive.source());
    }
}
