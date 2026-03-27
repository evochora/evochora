package org.evochora.compiler.features.proc;

import org.evochora.compiler.backend.emit.ConditionalUtils;
import org.evochora.compiler.backend.emit.IEmissionRule;
import org.evochora.compiler.model.ir.*;

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

    // Static counter across compilations — ensures unique label names across programs
    // compiled in the same JVM process. Prevents label hash collisions when programs
    // are placed adjacent in the world grid.
    private static final AtomicInteger safeRetCounter = new AtomicInteger(0);

    @Override
    public List<IrItem> apply(List<IrItem> items) {
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
                List<String> lrefParamNames = getParamNames(dir, "lrefParams");
                List<String> lvalParamNames = getParamNames(dir, "lvalParams");

                List<String> allDataParams = Stream.concat(refParamNames.stream(), valParamNames.stream()).collect(Collectors.toList());
                List<String> allLocationParams = Stream.concat(lrefParamNames.stream(), lvalParamNames.stream()).collect(Collectors.toList());

                // Prologue: POP all data parameters into FDRs
                for (int p = 0; p < allDataParams.size(); p++) {
                    out.add(IrInstruction.synthetic("POP", List.of(new IrReg("%FDR" + p)), dir.source()));
                }

                // Prologue: POPL all location parameters into FLRs
                for (int p = 0; p < allLocationParams.size(); p++) {
                    out.add(IrInstruction.synthetic("POPL", List.of(new IrReg("%FLR" + p)), dir.source()));
                }

                // Process body for RET instructions (handles epilogue generation)
                processBodyForRets(out, body, refParamNames, lrefParamNames);

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

    private void processBodyForRets(List<IrItem> out, List<IrItem> body, List<String> refParams, List<String> lrefParams) {
        int i = 0;
        while (i < body.size()) {
            IrItem currentItem = body.get(i);

            // Check for conditional RET
            if (i + 1 < body.size() && currentItem instanceof IrInstruction conditional && ConditionalUtils.isConditional(conditional.opcode())) {
                if (body.get(i + 1) instanceof IrInstruction ret && "RET".equals(ret.opcode())) {
                    handleConditionalRet(out, conditional, ret, refParams, lrefParams);
                    i += 2;
                    continue;
                }
            }

            // Handle standard RET
            if (currentItem instanceof IrInstruction ret && "RET".equals(ret.opcode())) {
                emitStandardEpilogue(out, ret, refParams, lrefParams);
                i++;
                continue;
            }

            out.add(currentItem);
            i++;
        }
    }

    private void handleConditionalRet(List<IrItem> out, IrInstruction conditional, IrInstruction ret,
                                      List<String> refParams, List<String> lrefParams) {
        String label = "_safe_ret_" + safeRetCounter.getAndIncrement();
        String negatedOpcode = ConditionalUtils.getNegatedOpcode(conditional.opcode());

        out.add(IrInstruction.synthetic(negatedOpcode, conditional.operands(), conditional.source()));
        out.add(IrInstruction.synthetic("JMPI", List.of(new IrLabelRef(label)), conditional.source()));

        emitStandardEpilogue(out, ret, refParams, lrefParams);

        out.add(new IrLabelDef(label, ret.source()));
    }

    private void emitStandardEpilogue(List<IrItem> out, IrInstruction ret, List<String> refParams, List<String> lrefParams) {
        // Epilogue: PUSL LREF FLR parameters back to location stack (for caller write-back)
        if (lrefParams != null) {
            for (int p = 0; p < lrefParams.size(); p++) {
                out.add(IrInstruction.synthetic("PUSL", List.of(new IrReg("%FLR" + p)), ret.source()));
            }
        }

        // Epilogue: PUSH REF FDR parameters back to data stack (for caller write-back)
        if (refParams != null) {
            for (int p = 0; p < refParams.size(); p++) {
                out.add(IrInstruction.synthetic("PUSH", List.of(new IrReg("%FDR" + p)), ret.source()));
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
