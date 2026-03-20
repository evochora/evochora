package org.evochora.compiler.features.reg;

import org.evochora.compiler.backend.emit.EmissionContext;
import org.evochora.compiler.backend.emit.IEmissionContributor;

import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrItem;
import org.evochora.compiler.model.ir.IrValue;

/**
 * Extracts register alias metadata from {@code reg_alias} IR directives
 * and registers it in the {@link EmissionContext}.
 *
 * <p>The {@code reg_alias} directive is emitted by {@code RegNodeConverter}
 * and {@code PregNodeConverter} in Phase 7. Each directive carries a
 * module-qualified alias name and a target register name (e.g., {@code %DR7},
 * {@code %PR0}). This contributor converts register names to their numeric IDs
 * so the {@link org.evochora.compiler.api.ProgramArtifact} can include them
 * for debugger and frontend visualization.</p>
 */
public final class RegisterAliasEmissionContributor implements IEmissionContributor {

    @Override
    public void onItem(IrItem item, EmissionContext context) {
        if (!(item instanceof IrDirective dir)) return;
        if (!"reg_alias".equals(dir.name())) return;

        IrValue nameValue = dir.args().get("name");
        if (!(nameValue instanceof IrValue.Str nameStr)) return;

        IrValue registerValue = dir.args().get("register");
        if (!(registerValue instanceof IrValue.Str registerStr)) return;

        String aliasName = nameStr.value();
        String registerName = registerStr.value();
        Integer registerId = resolveRegisterId(registerName);

        if (registerId != null) {
            context.registerAlias(aliasName, registerId);
        }
    }

    private Integer resolveRegisterId(String registerName) {
        if (registerName.startsWith("%FPR")) {
            int index = Integer.parseInt(registerName.substring(4));
            return org.evochora.runtime.isa.Instruction.FPR_BASE + index;
        }
        if (registerName.startsWith("%PR")) {
            int index = Integer.parseInt(registerName.substring(3));
            return org.evochora.runtime.isa.Instruction.PR_BASE + index;
        }
        if (registerName.startsWith("%LR")) {
            int index = Integer.parseInt(registerName.substring(3));
            return org.evochora.runtime.isa.Instruction.LR_BASE + index;
        }
        if (registerName.startsWith("%DR")) {
            return Integer.parseInt(registerName.substring(3));
        }
        return null;
    }
}
