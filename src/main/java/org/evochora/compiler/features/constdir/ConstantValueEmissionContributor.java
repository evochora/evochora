package org.evochora.compiler.features.constdir;

import org.evochora.compiler.backend.emit.EmissionContext;
import org.evochora.compiler.backend.emit.IEmissionContributor;
import org.evochora.compiler.model.expression.Value;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrItem;
import org.evochora.compiler.model.ir.IrValue;

/**
 * Extracts the values of constants from {@code const_value} IR directives and registers them in
 * the {@link EmissionContext}, so that the {@link org.evochora.compiler.api.ProgramArtifact} can
 * show a constant's value where it is used.
 *
 * <p>The {@code const_value} directive is emitted by {@link ConstNodeConverter} in Phase 7, one
 * per constant definition, with the path of the constant and the value as text; the value is
 * filed under the path. A constant whose value only the layout knows carries its tree instead of
 * a text; its value is the one the layout recorded under the path, written as text.</p>
 */
public final class ConstantValueEmissionContributor implements IEmissionContributor {

    @Override
    public void onItem(IrItem item, EmissionContext context) {
        if (!(item instanceof IrDirective dir) || !"const_value".equals(dir.name())) {
            return;
        }
        if (!(dir.args().get("name") instanceof IrValue.Str name)) {
            return;
        }
        if (dir.args().get("value") instanceof IrValue.Str value) {
            context.constantValue(name.value(), value.value());
        } else if (dir.args().get("value") instanceof IrValue.Expression) {
            Value computed = context.namedValues().get(name.value());
            if (computed != null) {
                context.constantValue(name.value(), computed.text());
            }
        }
    }
}
