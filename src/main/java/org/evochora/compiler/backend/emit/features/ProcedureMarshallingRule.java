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

                // Kopiere den Rumpf und füge den Epilog vor JEDEM RET ein
                boolean sawRet = false;
                for (IrItem bi : body) {
                    if (bi instanceof IrInstruction instr && "RET".equals(instr.opcode())) {
                        // Epilog vor RET einfügen
                        for (int p = 0; p < arity; p++) {
                            out.add(new IrInstruction("PUSH", List.of(new IrReg("%FPR" + p)), it.source()));
                        }
                        out.add(instr);
                        sawRet = true;
                    } else {
                        out.add(bi);
                    }
                }

                // Falls kein RET im Körper vorhanden war, nur Epilog einfügen (kein implizites RET)
                if (!sawRet) {
                    for (int p = 0; p < arity; p++) {
                        out.add(new IrInstruction("PUSH", List.of(new IrReg("%FPR" + p)), it.source()));
                    }
                }

                // proc_exit anfügen
                if (j < items.size()) {
                    out.add(items.get(j));
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