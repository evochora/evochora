package org.evochora.compiler.features.importdir;

import org.evochora.compiler.backend.emit.EmissionContext;
import org.evochora.compiler.backend.emit.IEmissionContributor;
import org.evochora.compiler.model.expression.Value;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrItem;
import org.evochora.compiler.model.ir.IrValue;

/**
 * Registers the value of each value requirement in the {@link EmissionContext}, as the value of
 * a constant, so that the {@link org.evochora.compiler.api.ProgramArtifact} can show it where the
 * value is used.
 *
 * <p>The {@code value} directive of {@code importdir} is emitted by
 * {@link ValueRequirementNodeConverter} in phase 7, one per value requirement and placement, with
 * the path of the value and the value as text; the value is filed under the path. A value only
 * the layout computes carries its tree or its own name instead of a text; its value is the one
 * the layout recorded under the path, written as text. A value whose text is the path of a
 * constant or a value filed before it takes that definition's value. The {@code bound_value} directive of an
 * import files nothing: the requirement's line files the value it bound.</p>
 */
public final class ValueEmissionContributor implements IEmissionContributor {

    @Override
    public void onItem(IrItem item, EmissionContext context) {
        if (!(item instanceof IrDirective dir) || !"importdir".equals(dir.namespace()) || !"value".equals(dir.name())) {
            return;
        }
        if (!(dir.args().get("name") instanceof IrValue.Str name)) {
            return;
        }
        if (dir.args().get("value") instanceof IrValue.Str value) {
            // A name of a definition above stands for that definition's value; a label's name stays
            context.constantValue(name.value(), context.constantValues().getOrDefault(value.value(), value.value()));
        } else if (dir.args().get("value") instanceof IrValue.Expression) {
            Value computed = context.namedValues().get(name.value());
            if (computed == null) {
                throw new IllegalStateException("The layout recorded no value for the required value " + name.value() + ".");
            }
            context.constantValue(name.value(), computed.text());
        }
    }
}
