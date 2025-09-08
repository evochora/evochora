package org.evochora.compiler.backend.emit.features;

import org.evochora.compiler.backend.emit.IEmissionRule;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.ir.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures REF/VAL bindings on CALL instructions and stores them temporarily
 * in the LinkingContext, associated with the CALL instruction's IR object.
 * This runs before the Linker, so final addresses are not yet known.
 */
public final class RefValBindingCaptureRule implements IEmissionRule {

    /**
     * {@inheritDoc}
     */
    @Override
    public List<IrItem> apply(List<IrItem> items, LinkingContext linkingContext) {
        for (IrItem item : items) {
            if (item instanceof IrInstruction call && "CALL".equalsIgnoreCase(call.opcode())) {
                // Check if this CALL has REF/VAL operands
                if (!call.refOperands().isEmpty() || !call.valOperands().isEmpty()) {
                    List<String> actualRegs = new ArrayList<>();
                    
                    // Add REF operands first (they come first in the procedure definition)
                    for (IrOperand refOperand : call.refOperands()) {
                        if (refOperand instanceof IrReg reg) {
                            actualRegs.add(reg.name());
                        }
                    }
                    
                    // Add VAL operands second
                    for (IrOperand valOperand : call.valOperands()) {
                        if (valOperand instanceof IrReg reg) {
                            actualRegs.add(reg.name());
                        }
                        // Note: VAL literals are not added to bindings as they don't have register names
                    }
                    
                    if (!actualRegs.isEmpty()) {
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
