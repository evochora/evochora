package org.evochora.compiler.backend.link.features;

import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.backend.link.ILinkingRule;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.frontend.semantics.SymbolTable;
import org.evochora.compiler.ir.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Resolves IrLabelRef operands to n-D delta vectors using the layout mapping.
 * In this version, the vector represents the program-relative coordinate of the label.
 */
public class LabelRefLinkingRule implements ILinkingRule {

    private final SymbolTable symbolTable;

    public LabelRefLinkingRule(SymbolTable symbolTable) {
        this.symbolTable = symbolTable;
    }

    @Override
    public IrInstruction apply(IrInstruction instruction, LinkingContext context, LayoutResult layout) {
        List<IrOperand> ops = instruction.operands();
        if (ops == null || ops.isEmpty()) return instruction;

        List<IrOperand> rewritten = null;
        for (int i = 0; i < ops.size(); i++) {
            IrOperand op = ops.get(i);
            if (op instanceof IrLabelRef ref) {
                String labelNameToFind = ref.labelName();

                if (labelNameToFind.contains(".")) {
                    var symbolOpt = symbolTable.resolve(new org.evochora.compiler.frontend.lexer.Token(
                            null, labelNameToFind, null, instruction.source().lineNumber(), 0, instruction.source().fileName()
                    ));
                    if (symbolOpt.isPresent()) {
                        labelNameToFind = symbolOpt.get().name().text();
                    }
                }

                Integer targetAddr = layout.labelToAddress().get(labelNameToFind);
                if (targetAddr != null) {
                    int[] dstCoord = layout.linearAddressToCoord().get(targetAddr);
                    if (dstCoord != null) {
                        int[] absoluteVector = Arrays.copyOf(dstCoord, dstCoord.length);
                        if (rewritten == null) {
                            rewritten = new ArrayList<>(ops);
                        }
                        rewritten.set(i, new IrVec(absoluteVector));
                    }
                }
            }
        }
        return rewritten != null ? new IrInstruction(instruction.opcode(), rewritten, instruction.source()) : instruction;
    }
}