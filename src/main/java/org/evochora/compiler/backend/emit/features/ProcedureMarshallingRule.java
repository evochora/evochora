package org.evochora.compiler.backend.emit.features;

import org.evochora.compiler.backend.emit.IEmissionRule;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.ir.*;

import java.util.ArrayList;
import java.util.List;

public class ProcedureMarshallingRule implements IEmissionRule {

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

                // Prolog: Parameter vom Stack in die %FPR-Register laden
                for (int p = arity - 1; p >= 0; p--) {
                    out.add(new IrInstruction("POP", List.of(new IrReg("%FPR" + p)), it.source()));
                }

                // Finde den Rumpf der Prozedur bis zum zugehörigen proc_exit
                int j = i + 1;
                List<IrItem> body = new ArrayList<>();
                for (; j < items.size(); j++) {
                    IrItem bi = items.get(j);
                    if (bi instanceof IrDirective d2 && "core".equals(d2.namespace()) && "proc_exit".equals(d2.name())) {
                        break;
                    }
                    body.add(bi);
                }

                // Finde die RET-Anweisung im Rumpf
                IrInstruction retInstruction = null;
                int retIndex = -1;
                for (int k = 0; k < body.size(); k++) {
                    if (body.get(k) instanceof IrInstruction instr && "RET".equals(instr.opcode())) {
                        retInstruction = instr;
                        retIndex = k;
                        break;
                    }
                }

                // Füge den Rumpf bis (exklusive) RET hinzu
                if (retIndex != -1) {
                    out.addAll(body.subList(0, retIndex));
                } else {
                    out.addAll(body); // Füge den gesamten Rumpf hinzu, wenn kein RET gefunden wurde
                }

                // Epilog: Schreibe die Werte aus den %FPR-Registern zurück auf den Stack
                for (int p = 0; p < arity; p++) {
                    out.add(new IrInstruction("PUSH", List.of(new IrReg("%FPR" + p)), it.source()));
                }

                // Füge die RET-Anweisung (falls vorhanden) und das proc_exit hinzu
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