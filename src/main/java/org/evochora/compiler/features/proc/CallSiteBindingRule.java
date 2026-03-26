package org.evochora.compiler.features.proc;

import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.backend.link.ILinkingRule;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.model.ir.IrInstruction;
import org.evochora.compiler.model.ir.IrOperand;
import org.evochora.compiler.model.ir.IrReg;
import org.evochora.runtime.isa.RegisterBank;

import java.util.HashMap;
import java.util.Map;

/**
 * Collects parameter register bindings for CALL instructions. For each CALL,
 * builds a map from formal register IDs (FDR_BASE+i for data params, FLR_BASE+i
 * for location params) to source register IDs, stored in the linking context.
 */
public class CallSiteBindingRule implements ILinkingRule {

    @Override
    public IrInstruction apply(IrInstruction instruction, LinkingContext context, LayoutResult layout) {
        if (!(instruction instanceof IrCallInstruction call)) return instruction;

        IInstructionSet isa = context.isa();
        Map<Integer, Integer> bindings = new HashMap<>();

        int dataIndex = 0;
        for (IrOperand op : call.refOperands()) {
            if (op instanceof IrReg reg) {
                bindings.put(RegisterBank.FDR.base + dataIndex, resolveReg(isa, reg.name()));
            }
            dataIndex++;
        }
        for (IrOperand op : call.valOperands()) {
            if (op instanceof IrReg reg) {
                bindings.put(RegisterBank.FDR.base + dataIndex, resolveReg(isa, reg.name()));
            }
            dataIndex++;
        }

        int locationIndex = 0;
        for (IrOperand op : call.lrefOperands()) {
            if (op instanceof IrReg reg) {
                bindings.put(RegisterBank.FLR.base + locationIndex, resolveReg(isa, reg.name()));
            }
            locationIndex++;
        }
        for (IrOperand op : call.lvalOperands()) {
            if (op instanceof IrReg reg) {
                bindings.put(RegisterBank.FLR.base + locationIndex, resolveReg(isa, reg.name()));
            }
            locationIndex++;
        }

        if (!bindings.isEmpty()) {
            context.callSiteBindings().put(context.currentAddress(), bindings);
        }
        return instruction;
    }

    private int resolveReg(IInstructionSet isa, String regName) {
        return isa.resolveRegisterToken(regName).orElseThrow(() ->
                new IllegalStateException("Cannot resolve register '" + regName
                        + "' — should have been validated in semantic analysis"));
    }
}
