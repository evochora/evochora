package org.evochora.compiler.features.proc;

import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.backend.link.ILinkingRule;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.model.ir.IrInstruction;
import org.evochora.compiler.model.ir.IrOperand;
import org.evochora.compiler.model.ir.IrReg;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects parameter register bindings for CALL instructions. For each CALL,
 * the register names from ref and val operands are resolved to numeric IDs
 * and stored in the linking context for downstream runtime use.
 */
public class CallSiteBindingRule implements ILinkingRule {

    @Override
    public IrInstruction apply(IrInstruction instruction, LinkingContext context, LayoutResult layout) {
        if (!(instruction instanceof IrCallInstruction call)) return instruction;

        IInstructionSet isa = context.isa();
        List<String> regNames = new ArrayList<>();
        for (IrOperand op : call.refOperands()) {
            if (op instanceof IrReg reg) regNames.add(reg.name());
        }
        for (IrOperand op : call.valOperands()) {
            if (op instanceof IrReg reg) regNames.add(reg.name());
        }
        if (!regNames.isEmpty()) {
            int[] ids = new int[regNames.size()];
            for (int j = 0; j < regNames.size(); j++) {
                String regName = regNames.get(j);
                ids[j] = isa.resolveRegisterToken(regName).orElseThrow(() ->
                        new IllegalStateException("Cannot resolve register '" + regName
                                + "' — should have been validated in semantic analysis"));
            }
            context.callSiteBindings().put(context.currentAddress(), ids);
        }
        return instruction;
    }
}
