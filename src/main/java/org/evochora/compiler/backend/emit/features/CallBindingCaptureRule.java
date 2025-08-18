package org.evochora.compiler.backend.emit.features;

import org.evochora.compiler.backend.emit.IEmissionRule;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.ir.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures .WITH bindings on CALL instructions and stores them temporarily
 * in the LinkingContext, associated with the CALL instruction's IR object.
 * This runs before the Linker, so final addresses are not yet known.
 */
public final class CallBindingCaptureRule implements IEmissionRule {

    @Override
    public List<IrItem> apply(List<IrItem> items, LinkingContext linkingContext) {
        for (int i = 0; i < items.size(); i++) {
            IrItem current = items.get(i);
            if (current instanceof IrInstruction call && "CALL".equalsIgnoreCase(call.opcode())) {
                // Check if the preceding instruction was a core:call_with directive
                if (i > 0 && items.get(i - 1) instanceof IrDirective dir && "core".equals(dir.namespace()) && "call_with".equals(dir.name())) {
                    IrValue.ListVal listVal = (IrValue.ListVal) dir.args().get("actuals");
                    if (listVal != null) {
                        List<String> actualRegs = new ArrayList<>();
                        for (IrValue v : listVal.elements()) {
                            if (v instanceof IrValue.Str s) {
                                actualRegs.add(s.value());
                            }
                        }
                        // Temporarily associate the bindings with the call instruction object
                        linkingContext.addPendingBinding(call, actualRegs);
                    }
                }
            }
        }
        // This rule only captures data; it does not modify the IR stream.
        return items;
    }
}