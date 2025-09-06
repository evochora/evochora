package org.evochora.compiler.backend.emit.features;

import org.evochora.compiler.backend.emit.ConditionalUtils;
import org.evochora.compiler.backend.emit.IEmissionRule;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.ir.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Inserts procedure prologue and epilogue code for parameter marshalling.
 * It handles standard and conditional RET instructions.
 */
public class ProcedureMarshallingRule implements IEmissionRule {

    private static final AtomicInteger safeRetCounter = new AtomicInteger(0);

    @Override
    public List<IrItem> apply(List<IrItem> items, LinkingContext linkingContext) {
        List<IrItem> out = new ArrayList<>(items.size() + 16);
        int i = 0;
        while (i < items.size()) {
            IrItem it = items.get(i);
            if (it instanceof IrDirective dir && "core".equals(dir.namespace()) && "proc_enter".equals(dir.name())) {
                int bodyEndIndex = findBodyEnd(items, i);
                List<IrItem> body = items.subList(i + 1, bodyEndIndex);
                out.add(it);

                List<String> refParamNames = getParamNames(dir, "refParams");
                List<String> valParamNames = getParamNames(dir, "valParams");

                if (!refParamNames.isEmpty() || !valParamNames.isEmpty()) {
                    // New REF/VAL syntax
                    handleNewSyntax(out, dir, body, refParamNames, valParamNames);
                } else {
                    // Old ".PROC WITH" syntax
                    handleLegacySyntax(out, dir, body);
                }

                if (bodyEndIndex < items.size()) {
                    out.add(items.get(bodyEndIndex)); // Add proc_exit
                }
                i = bodyEndIndex + 1;
            } else {
                out.add(it);
                i++;
            }
        }
        return out;
    }

    private void handleNewSyntax(List<IrItem> out, IrDirective enterDirective, List<IrItem> body, List<String> refParams, List<String> valParams) {
        List<String> allParams = Stream.concat(refParams.stream(), valParams.stream()).collect(Collectors.toList());

        // Prologue: POP all parameters into FPRs
        for (int p = 0; p < allParams.size(); p++) {
            out.add(new IrInstruction("POP", List.of(new IrReg("%FPR" + p)), enterDirective.source()));
        }

        // Process body for RET instructions
        processBodyForRets(out, body, refParams, -1);
    }

    private void handleLegacySyntax(List<IrItem> out, IrDirective enterDirective, List<IrItem> body) {
        long arityLong = enterDirective.args().getOrDefault("arity", new IrValue.Int64(0)) instanceof IrValue.Int64 iv ? iv.value() : 0L;
        int arity = (int) Math.max(0, Math.min(8, arityLong));

        // Prologue: Load parameters from the stack into the %FPR registers
        for (int p = arity - 1; p >= 0; p--) {
            out.add(new IrInstruction("POP", List.of(new IrReg("%FPR" + p)), enterDirective.source()));
        }

        // Process body for RET instructions
        processBodyForRets(out, body, null, arity);
    }

    private void processBodyForRets(List<IrItem> out, List<IrItem> body, List<String> refParams, int arity) {
        int i = 0;
        while (i < body.size()) {
            IrItem currentItem = body.get(i);

            // Check for conditional RET
            if (i + 1 < body.size() && currentItem instanceof IrInstruction conditional && ConditionalUtils.isConditional(conditional.opcode())) {
                if (body.get(i + 1) instanceof IrInstruction ret && "RET".equals(ret.opcode())) {
                    handleConditionalRet(out, conditional, ret, refParams, arity);
                    i += 2;
                    continue;
                }
            }

            // Handle standard RET
            if (currentItem instanceof IrInstruction ret && "RET".equals(ret.opcode())) {
                emitStandardEpilogue(out, ret, refParams, arity);
                i++;
                continue;
            }

            out.add(currentItem);
            i++;
        }
    }

    private void handleConditionalRet(List<IrItem> out, IrInstruction conditional, IrInstruction ret, List<String> refParams, int arity) {
        String label = "_safe_ret_" + safeRetCounter.getAndIncrement();
        String negatedOpcode = ConditionalUtils.getNegatedOpcode(conditional.opcode());

        out.add(new IrInstruction(negatedOpcode, conditional.operands(), conditional.source()));
        out.add(new IrInstruction("JMPI", List.of(new IrLabelRef(label)), conditional.source()));

        emitStandardEpilogue(out, ret, refParams, arity);

        out.add(new IrLabelDef(label, ret.source()));
    }

    private void emitStandardEpilogue(List<IrItem> out, IrInstruction ret, List<String> refParams, int arity) {
        if (refParams != null) { // New REF/VAL syntax
            for (int p = 0; p < refParams.size(); p++) {
                out.add(new IrInstruction("PUSH", List.of(new IrReg("%FPR" + p)), ret.source()));
            }
        } else { // Legacy arity syntax
            for (int p = 0; p < arity; p++) {
                out.add(new IrInstruction("PUSH", List.of(new IrReg("%FPR" + p)), ret.source()));
            }
        }
        out.add(ret);
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