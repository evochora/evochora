package org.evochora.compiler.features.proc;

import org.evochora.compiler.backend.emit.EmissionContext;
import org.evochora.compiler.backend.emit.IEmissionContributor;
import org.evochora.compiler.api.ParamInfo;
import org.evochora.compiler.api.ParamType;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrItem;
import org.evochora.compiler.model.ir.IrValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts procedure parameter metadata from {@code proc_enter} IR directives
 * and registers it in the {@link EmissionContext}.
 *
 * <p>The {@code proc_enter} directive is emitted by {@code ProcedureNodeConverter}
 * in Phase 7 with the module-qualified procedure name and parameter lists. This
 * contributor reads that data so the {@link Emitter} can include it in the
 * {@link org.evochora.compiler.api.ProgramArtifact} without any side-channel
 * from the Compiler.</p>
 */
public final class ProcedureEmissionContributor implements IEmissionContributor {

    @Override
    public void onItem(IrItem item, EmissionContext context) {
        if (!(item instanceof IrDirective dir)) return;
        if (!"proc_enter".equals(dir.name())) return;

        IrValue nameValue = dir.args().get("name");
        if (!(nameValue instanceof IrValue.Str nameStr)) return;

        String qualifiedName = nameStr.value();
        List<ParamInfo> params = new ArrayList<>();

        IrValue refParamsValue = dir.args().get("refParams");
        if (refParamsValue instanceof IrValue.ListVal refList) {
            for (IrValue v : refList.elements()) {
                if (v instanceof IrValue.Str s) {
                    params.add(new ParamInfo(s.value(), ParamType.REF));
                }
            }
        }

        IrValue valParamsValue = dir.args().get("valParams");
        if (valParamsValue instanceof IrValue.ListVal valList) {
            for (IrValue v : valList.elements()) {
                if (v instanceof IrValue.Str s) {
                    params.add(new ParamInfo(s.value(), ParamType.VAL));
                }
            }
        }

        IrValue lrefParamsValue = dir.args().get("lrefParams");
        if (lrefParamsValue instanceof IrValue.ListVal lrefList) {
            for (IrValue v : lrefList.elements()) {
                if (v instanceof IrValue.Str s) {
                    params.add(new ParamInfo(s.value(), ParamType.LREF));
                }
            }
        }

        IrValue lvalParamsValue = dir.args().get("lvalParams");
        if (lvalParamsValue instanceof IrValue.ListVal lvalList) {
            for (IrValue v : lvalList.elements()) {
                if (v instanceof IrValue.Str s) {
                    params.add(new ParamInfo(s.value(), ParamType.LVAL));
                }
            }
        }

        // Old-style WITH parameters: derive from arity (no names in directive)
        IrValue arityValue = dir.args().get("arity");
        if (arityValue instanceof IrValue.Int64 arityInt && arityInt.value() > 0) {
            for (int i = 0; i < arityInt.value(); i++) {
                params.add(new ParamInfo("param" + i, ParamType.WITH));
            }
        }

        context.registerProcedure(qualifiedName, params);
    }
}
