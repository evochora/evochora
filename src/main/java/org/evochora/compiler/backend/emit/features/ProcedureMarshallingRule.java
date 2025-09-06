package org.evochora.compiler.backend.emit.features;

import org.evochora.compiler.backend.emit.IEmissionRule;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.ir.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Inserts procedure prologue and epilogue code for parameter marshalling.
 * It transforms `proc_enter` and `proc_exit` directives into PUSH/POP sequences
 * to handle formal parameters.
 */
public class ProcedureMarshallingRule implements IEmissionRule {

    @Override
    public List<IrItem> apply(List<IrItem> items, LinkingContext linkingContext) {
        List<IrItem> out = new ArrayList<>(items.size() + 16);
        int i = 0;
        while (i < items.size()) {
            IrItem it = items.get(i);
            if (it instanceof IrDirective dir && "core".equals(dir.namespace()) && "proc_enter".equals(dir.name())) {

                List<String> refParamNames = getParamNames(dir, "refParams");
                List<String> valParamNames = getParamNames(dir, "valParams");

                if (!refParamNames.isEmpty() || !valParamNames.isEmpty()) {
                    // New REF/VAL syntax
                    out.add(it);
                    handleNewSyntax(out, items, i, dir, refParamNames, valParamNames);
                    int j = i + 1;
                    while (j < items.size() && !(items.get(j) instanceof IrDirective d2 && "core".equals(d2.namespace()) && "proc_exit".equals(d2.name()))) {
                        j++;
                    }
                    i = j;
                } else {
                    // Old ".PROC WITH" syntax - original logic
                    long arityLong = dir.args().getOrDefault("arity", new IrValue.Int64(0)) instanceof IrValue.Int64 iv ? iv.value() : 0L;
                    int arity = (int) Math.max(0, Math.min(8, arityLong));
                    out.add(it);

                    // Prologue: Load parameters from the stack into the %FPR registers
                    for (int p = arity - 1; p >= 0; p--) {
                        out.add(new IrInstruction("POP", List.of(new IrReg("%FPR" + p)), it.source()));
                    }

                    // Find the body of the procedure up to the corresponding proc_exit
                    int j = i + 1;
                    List<IrItem> body = new ArrayList<>();
                    for (; j < items.size(); j++) {
                        IrItem bi = items.get(j);
                        if (bi instanceof IrDirective d2 && "core".equals(d2.namespace()) && "proc_exit".equals(d2.name())) {
                            break;
                        }
                        body.add(bi);
                    }

                    // Copy the body and insert the epilogue before EACH RET
                    boolean sawRet = false;
                    for (IrItem bi : body) {
                        if (bi instanceof IrInstruction instr && "RET".equals(instr.opcode())) {
                            // Insert epilogue before RET
                            for (int p = 0; p < arity; p++) {
                                out.add(new IrInstruction("PUSH", List.of(new IrReg("%FPR" + p)), it.source()));
                            }
                            out.add(instr);
                            sawRet = true;
                        } else {
                            out.add(bi);
                        }
                    }

                    // If no RET was present in the body, only insert epilogue (no implicit RET)
                    if (!sawRet) {
                        for (int p = 0; p < arity; p++) {
                            out.add(new IrInstruction("PUSH", List.of(new IrReg("%FPR" + p)), it.source()));
                        }
                    }

                    // Append proc_exit
                    if (j < items.size()) {
                        out.add(items.get(j));
                    }

                    i = j + 1;
                    continue;
                }
            } else {
                out.add(it);
                i++;
            }
        }
        return out;
    }

    private void handleNewSyntax(List<IrItem> out, List<IrItem> items, int startIndex, IrDirective enterDirective, List<String> refParams, List<String> valParams) {
        List<String> allParams = Stream.concat(refParams.stream(), valParams.stream()).collect(Collectors.toList());

        // Prologue: POP all parameters into FPRs
        for (int p = 0; p < allParams.size(); p++) {
            out.add(new IrInstruction("POP", List.of(new IrReg("%FPR" + p)), enterDirective.source()));
        }

        // Find body and handle epilogue
        int bodyEndIndex = findBodyEnd(items, startIndex);
        List<IrItem> body = items.subList(startIndex + 1, bodyEndIndex);

        for (IrItem bodyItem : body) {
            if (bodyItem instanceof IrInstruction instr && "RET".equals(instr.opcode())) {
                // Epilogue: PUSH only REF parameters before RET
                for (int p = 0; p < refParams.size(); p++) {
                    out.add(new IrInstruction("PUSH", List.of(new IrReg("%FPR" + p)), instr.source()));
                }
                out.add(instr); // Add the RET instruction itself
            } else {
                out.add(bodyItem);
            }
        }

        if (bodyEndIndex < items.size()) {
            out.add(items.get(bodyEndIndex)); // Add proc_exit
        }
    }

    private int findBodyEnd(List<IrItem> items, int startIndex) {
        int j = startIndex + 1;
        while (j < items.size()) {
            IrItem item = items.get(j);
            if (item instanceof IrDirective d && "core".equals(d.namespace()) && "proc_exit".equals(d.name())) {
                return j;
            }
            j++;
        }
        return j;
    }

    private List<String> getParamNames(IrDirective dir, String key) {
        IrValue value = dir.args().get(key);
        if (value instanceof IrValue.ListVal listVal) {
            return listVal.elements().stream()
                .filter(v -> v instanceof IrValue.Str)
                .map(v -> ((IrValue.Str) v).value())
                .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}