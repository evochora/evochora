package org.evochora.compiler.features.proc;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.backend.emit.ConditionalUtils;
import org.evochora.compiler.backend.emit.IEmissionRule;
import org.evochora.compiler.model.ir.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Inserts caller-side PUSH/POP sequences around CALL instructions.
 * This rule handles both standard and conditional calls, including REF/VAL parameter passing.
 */
public final class CallerMarshallingRule implements IEmissionRule {

    // Static counter across compilations — ensures unique label names across programs
    // compiled in the same JVM process. Prevents label hash collisions when programs
    // are placed adjacent in the world grid.
    private static final AtomicInteger safeCallCounter = new AtomicInteger(0);

    @Override
    public List<IrItem> apply(List<IrItem> items) {
        List<IrItem> out = new ArrayList<>(items.size() + 8);
        int i = 0;
        while (i < items.size()) {
            IrItem currentItem = items.get(i);

            // Look for a conditional instruction followed by a CALL.
            if (i + 1 < items.size() && currentItem instanceof IrInstruction conditional && ConditionalUtils.isConditional(conditional.opcode())) {
                if (items.get(i + 1) instanceof IrCallInstruction call) {
                    // This is a conditional CALL.
                    handleConditionalCall(out, conditional, call);
                    i += 2; // Consumed both the conditional and the CALL.
                    continue;
                }
            }

            // Handle a standard, non-conditional CALL (including REF/VAL/LREF/LVAL).
            if (currentItem instanceof IrCallInstruction call) {
                if (!call.refOperands().isEmpty() || !call.valOperands().isEmpty()
                        || !call.lrefOperands().isEmpty() || !call.lvalOperands().isEmpty()) {
                    emitStandardMarshalling(out, call);
                } else {
                    out.add(call); // Plain CALL with no params.
                }
                i++;
                continue;
            }

            // Legacy logic for `core:call_with`
            if (currentItem instanceof IrDirective dir && "core".equals(dir.namespace()) && "call_with".equals(dir.name())) {
                if (i + 1 < items.size() && items.get(i + 1) instanceof IrInstruction call && "CALL".equals(call.opcode())) {
                    handleLegacyCallWith(out, dir, call, items.get(i + 1));
                    i += 2;
                    continue;
                }
                i++; // Drop directive if not followed by CALL.
                continue;
            }

            out.add(currentItem);
            i++;
        }
        return out;
    }

    private void handleConditionalCall(List<IrItem> out, IrInstruction conditional, IrCallInstruction call) {
        String label = "_safe_call_" + safeCallCounter.getAndIncrement();
        String negatedOpcode = ConditionalUtils.getNegatedOpcode(conditional.opcode());

        // 1. Emit negated conditional and jump.
        out.add(IrInstruction.synthetic(negatedOpcode, conditional.operands(), conditional.source()));
        out.add(IrInstruction.synthetic("JMPI", List.of(new IrLabelRef(label)), conditional.source()));

        // 2. Emit the marshalling sequence for the CALL.
        emitStandardMarshalling(out, call);

        // 3. Emit the target label for the jump.
        out.add(new IrLabelDef(label, call.source()));
    }

    private void emitStandardMarshalling(List<IrItem> out, IrCallInstruction call) {
        // Pre-call: Push arguments (VALs then REFs, in reverse order).
        for (int j = call.valOperands().size() - 1; j >= 0; j--) {
            IrOperand operand = call.valOperands().get(j);
            if (operand instanceof IrImm imm) {
                out.add(IrInstruction.synthetic("PUSI", List.of(imm), call.source()));
            } else if (operand instanceof IrLabelRef) {
                // Labels as VAL parameters should be pushed as vectors (addresses)
                out.add(IrInstruction.synthetic("PUSV", List.of(operand), call.source()));
            } else if (operand instanceof IrTypedImm typedImm) {
                // Keep IrTypedImm to preserve type information for display
                out.add(IrInstruction.synthetic("PUSI", List.of(typedImm), call.source()));
            } else {
                out.add(IrInstruction.synthetic("PUSH", List.of(operand), call.source()));
            }
        }
        for (int j = call.refOperands().size() - 1; j >= 0; j--) {
            out.add(IrInstruction.synthetic("PUSH", List.of(call.refOperands().get(j)), call.source()));
        }

        // Pre-call: Push location arguments (LVAL then LREF, in reverse order) onto location stack
        for (int j = call.lvalOperands().size() - 1; j >= 0; j--) {
            IrOperand operand = call.lvalOperands().get(j);
            if (operand instanceof IrLabelRef) {
                out.add(IrInstruction.synthetic("PSLI", List.of(operand), call.source()));
            } else {
                out.add(IrInstruction.synthetic("PUSL", List.of(operand), call.source()));
            }
        }
        for (int j = call.lrefOperands().size() - 1; j >= 0; j--) {
            out.add(IrInstruction.synthetic("PUSL", List.of(call.lrefOperands().get(j)), call.source()));
        }

        // The CALL itself.
        out.add(call);

        // Post-call: Pop LREF args from location stack (write-back modified values to source registers)
        for (int j = call.lrefOperands().size() - 1; j >= 0; j--) {
            out.add(IrInstruction.synthetic("POPL", List.of(call.lrefOperands().get(j)), call.source()));
        }

        // Post-call: Pop REF args from data stack (write-back modified values to source registers)
        for (int j = call.refOperands().size() - 1; j >= 0; j--) {
            out.add(IrInstruction.synthetic("POP", List.of(call.refOperands().get(j)), call.source()));
        }
    }

    private void handleLegacyCallWith(List<IrItem> out, IrDirective dir, IrInstruction call, IrItem nextItem) {
        IrValue.ListVal listVal = (IrValue.ListVal) dir.args().get("actuals");
        List<IrValue> vals = listVal != null ? listVal.elements() : List.of();
        List<String> actualRegs = new ArrayList<>(vals.size());
        for (IrValue v : vals) {
            if (v instanceof IrValue.Str s) actualRegs.add(s.value());
        }

        SourceInfo originalSourceInfo = call.source();
        for (String r : actualRegs) {
            out.add(IrInstruction.synthetic("PUSH", List.of(new IrReg(r)), originalSourceInfo));
        }
        // Enrich the CALL with refOperands so the Linker can extract bindings directly from the IR
        List<IrOperand> enrichedRefOps = actualRegs.stream()
                .map(r -> (IrOperand) new IrReg(r))
                .toList();
        out.add(new IrCallInstruction(call.opcode(), call.operands(),
                enrichedRefOps, List.of(), List.of(), List.of(), call.source()));
        for (int a = actualRegs.size() - 1; a >= 0; a--) {
            out.add(IrInstruction.synthetic("POP", List.of(new IrReg(actualRegs.get(a))), originalSourceInfo));
        }
    }
}
