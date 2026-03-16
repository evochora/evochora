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
        IInstructionSet isa = context.isa();
        if (!"CALL".equalsIgnoreCase(instruction.opcode())) return instruction;

        List<String> regNames = new ArrayList<>();
        for (IrOperand op : instruction.refOperands()) {
            if (op instanceof IrReg reg) regNames.add(reg.name());
        }
        for (IrOperand op : instruction.valOperands()) {
            if (op instanceof IrReg reg) regNames.add(reg.name());
        }
        if (!regNames.isEmpty()) {
            int[] ids = new int[regNames.size()];
            for (int j = 0; j < regNames.size(); j++) {
                ids[j] = isa.resolveRegisterToken(regNames.get(j)).orElse(-1);
            }
            context.callSiteBindings().put(context.currentAddress(), ids);
        }
        return instruction;
    }
}
