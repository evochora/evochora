package org.evochora.compiler.features.importdir;

import org.evochora.compiler.backend.emit.EmissionContext;
import org.evochora.compiler.backend.emit.IEmissionContributor;
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
 * the path of the value and the value as text; the value is filed under the path.</p>
 */
public final class ValueEmissionContributor implements IEmissionContributor {

    @Override
    public void onItem(IrItem item, EmissionContext context) {
        if (!(item instanceof IrDirective dir) || !"importdir".equals(dir.namespace()) || !"value".equals(dir.name())) {
            return;
        }
        if (dir.args().get("name") instanceof IrValue.Str name && dir.args().get("value") instanceof IrValue.Str value) {
            context.constantValue(name.value(), value.value());
        }
    }
}
