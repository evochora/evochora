package org.evochora.compiler.features.constdir;

import org.evochora.compiler.api.DefinitionKey;
import org.evochora.compiler.backend.emit.EmissionContext;
import org.evochora.compiler.backend.emit.IEmissionContributor;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrItem;
import org.evochora.compiler.model.ir.IrValue;

/**
 * Extracts the values of constants from {@code const_value} IR directives and registers them in
 * the {@link EmissionContext}, so that the {@link org.evochora.compiler.api.ProgramArtifact} can
 * show a constant's value where it is used.
 *
 * <p>The {@code const_value} directive is emitted by {@link ConstNodeConverter} in Phase 7, one
 * per constant definition, with the module-qualified name, the scope it is defined in and the
 * value as text; the value is filed under the {@link DefinitionKey} of the constant.</p>
 */
public final class ConstantValueEmissionContributor implements IEmissionContributor {

    @Override
    public void onItem(IrItem item, EmissionContext context) {
        if (!(item instanceof IrDirective dir) || !"const_value".equals(dir.name())) {
            return;
        }
        if (dir.args().get("name") instanceof IrValue.Str name && dir.args().get("value") instanceof IrValue.Str value) {
            String scope = dir.args().get("scope") instanceof IrValue.Str scopeStr ? scopeStr.value() : null;
            context.constantValue(DefinitionKey.of(name.value(), scope), value.value());
        }
    }
}
