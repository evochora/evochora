package org.evochora.compiler.backend.emit.features;

import org.evochora.compiler.backend.emit.IEmissionRule;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.ir.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Inserts PUSH/POP/SETR scaffolding for procedure parameter marshalling,
 * based purely on IR patterns (CALL with formal params declared in PROC).
 *
 * Assumptions:
 * - Formal parameter registers are %FPR0..%FPR7 (by convention of the ISA)
 * - CALL instructions carry no explicit .WITH in IR; actuals must be conveyed via metadata later
 *
 * For now, this rule only adds callee prolog/epilog around PROC body using %FPRx.
 * Caller marshalling will be added when CALL actuals are available (future step).
 */
public final class ProcedureMarshallingRule implements IEmissionRule {

    @Override
    public List<IrItem> apply(List<IrItem> items, LinkingContext linkingContext) {
        List<IrItem> out = new ArrayList<>(items.size() + 16);
        int i = 0;
        while (i < items.size()) {
            IrItem it = items.get(i);
            if (it instanceof IrDirective dir && "core".equals(dir.namespace()) && "proc_enter".equals(dir.name())) {
                long arityLong = dir.args().getOrDefault("arity", new IrValue.Int64(0)) instanceof IrValue.Int64 iv ? iv.value() : 0L;
                int arity = (int) Math.max(0, Math.min(8, arityLong));
                out.add(it);
                // Prolog: POP %FPR(arity-1..0)
                for (int p = arity - 1; p >= 0; p--) {
                    out.add(new IrInstruction("POP", List.of(new IrReg("%FPR" + p)), it.source()));
                }

                // Body bis zum proc_exit finden
                int j = i + 1;
                List<IrItem> body = new ArrayList<>();
                for (; j < items.size(); j++) {
                    IrItem bi = items.get(j);
                    if (bi instanceof IrDirective d2 && "core".equals(d2.namespace()) && "proc_exit".equals(d2.name())) break;
                    body.add(bi);
                }

                // Epilog VOR dem RET einfügen
                IrInstruction retInstruction = null;
                int retIndex = -1;
                for (int k = body.size() - 1; k >= 0; k--) {
                    if (body.get(k) instanceof IrInstruction instr && "RET".equals(instr.opcode())) {
                        retInstruction = instr;
                        retIndex = k;
                        break;
                    }
                }

                // Body OHNE das RET hinzufügen
                if (retIndex != -1) {
                    out.addAll(body.subList(0, retIndex));
                } else {
                    out.addAll(body); // Fallback, falls kein RET gefunden wurde
                }

                // PUSH-Befehle für die Rückgabewerte
                for (int p = 0; p < arity; p++) {
                    out.add(new IrInstruction("PUSH", List.of(new IrReg("%FPR" + p)), it.source()));
                }

                // RET (falls gefunden) und proc_exit hinzufügen
                if (retInstruction != null) {
                    out.add(retInstruction);
                }
                if (j < items.size()) {
                    out.add(items.get(j)); // proc_exit
                }

                i = j + 1;
                continue;
            }

            out.add(it);
            i++;
        }
        return out;
    }
}


