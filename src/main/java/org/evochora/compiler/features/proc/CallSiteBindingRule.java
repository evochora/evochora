package org.evochora.compiler.features.proc;

import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.backend.link.ILinkingRule;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.model.ir.IrInstruction;
import org.evochora.compiler.model.ir.IrOperand;
import org.evochora.compiler.model.ir.IrReg;

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
        int formalDataBase = baseOfBank(isa, "FDR");
        int formalLocationBase = baseOfBank(isa, "FLR");
        Map<Integer, Integer> bindings = new HashMap<>();

        int dataIndex = 0;
        for (IrOperand op : call.refOperands()) {
            if (!(op instanceof IrReg reg)) {
                throw new IllegalStateException("Expected IrReg for REF operand at index " + dataIndex
                        + ", got: " + op.getClass().getSimpleName());
            }
            bindings.put(formalDataBase + dataIndex, resolveReg(isa, reg.name()));
            dataIndex++;
        }
        for (IrOperand op : call.valOperands()) {
            // VAL can be IrImm/IrTypedImm (literals) — no source register binding for those
            if (op instanceof IrReg reg) {
                bindings.put(formalDataBase + dataIndex, resolveReg(isa, reg.name()));
            }
            dataIndex++;
        }

        int locationIndex = 0;
        for (IrOperand op : call.lrefOperands()) {
            if (!(op instanceof IrReg reg)) {
                throw new IllegalStateException("Expected IrReg for LREF operand at index " + locationIndex
                        + ", got: " + op.getClass().getSimpleName());
            }
            bindings.put(formalLocationBase + locationIndex, resolveReg(isa, reg.name()));
            locationIndex++;
        }
        for (IrOperand op : call.lvalOperands()) {
            // LVAL can be IrLabelRef (label resolved via PSLI) — no source register binding for those
            if (op instanceof IrReg reg) {
                bindings.put(formalLocationBase + locationIndex, resolveReg(isa, reg.name()));
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

    /**
     * The ID of the first register of the named bank; the formal parameter banks are bound by
     * position from there.
     */
    private static int baseOfBank(IInstructionSet isa, String bankName) {
        return isa.registerBanks().stream()
                .filter(bank -> bank.name().equals(bankName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("The instruction set has no register bank " + bankName))
                .base();
    }
}
