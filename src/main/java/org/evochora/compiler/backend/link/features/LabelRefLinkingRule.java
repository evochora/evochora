package org.evochora.compiler.backend.link.features;

import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.backend.link.ILinkingRule;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.frontend.semantics.SymbolTable;
import org.evochora.compiler.ir.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves IrLabelRef operands to hash values for fuzzy jump matching.
 * The hash value is derived from the label name and used by the runtime's
 * LabelIndex to find matching LABEL molecules using Hamming distance tolerance.
 */
public class LabelRefLinkingRule implements ILinkingRule {

    private final SymbolTable symbolTable;

    /**
     * Constructs a new label reference linking rule.
     * @param symbolTable The symbol table for resolving symbols.
     */
    public LabelRefLinkingRule(SymbolTable symbolTable) {
        this.symbolTable = symbolTable;
    }

    /**
     * {@inheritDoc}
     */
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
                    // Generate hash value from label name (19-bit, always positive)
                    // Use 19 bits (0x7FFFF) to ensure value is never interpreted as negative
                    int labelHash = labelNameToFind.hashCode() & 0x7FFFF;
                    if (rewritten == null) {
                        rewritten = new ArrayList<>(ops);
                    }
                    rewritten.set(i, new IrImm(labelHash));
                }
            }
        }
        return rewritten != null ? new IrInstruction(instruction.opcode(), rewritten, instruction.source()) : instruction;
    }
}