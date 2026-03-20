package org.evochora.compiler.features.label;

import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.backend.link.ILinkingRule;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.model.symbols.ModuleScope;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.compiler.model.ir.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves IrLabelRef operands to hash values for fuzzy jump matching.
 * The hash value is derived from the label name and used by the runtime's
 * LabelIndex to find matching LABEL molecules using Hamming distance tolerance.
 */
public class LabelRefLinkingRule implements ILinkingRule {

    @Override
    public IrInstruction apply(IrInstruction instruction, LinkingContext context, LayoutResult layout) {
        SymbolTable symbolTable = context.symbolTable();
        List<IrOperand> ops = instruction.operands();
        if (ops == null || ops.isEmpty()) return instruction;

        List<IrOperand> rewritten = null;
        for (int i = 0; i < ops.size(); i++) {
            IrOperand op = ops.get(i);
            if (op instanceof IrLabelRef ref) {
                String labelNameToFind = ref.labelName();

                if (labelNameToFind.contains(".")) {
                    // Cross-module reference: resolve alias to the target module's alias chain
                    int dotPos = labelNameToFind.indexOf('.');
                    String alias = labelNameToFind.substring(0, dotPos).toUpperCase();
                    String symName = labelNameToFind.substring(dotPos + 1).toUpperCase();

                    Optional<ModuleScope> currentModScope = symbolTable.getModuleScope(
                            context.currentAliasChain());
                    if (currentModScope.isPresent()) {
                        String targetChain = currentModScope.get().imports().get(alias);
                        if (targetChain == null) {
                            targetChain = currentModScope.get().usingBindings().get(alias);
                        }
                        if (targetChain != null) {
                            labelNameToFind = targetChain + "." + symName;
                        }
                    }
                } else {
                    // Local reference: qualify with the current alias chain from context
                    labelNameToFind = qualifyName(labelNameToFind, context);
                }

                Integer targetAddr = layout.labelToAddress().get(labelNameToFind);
                if (targetAddr != null) {
                    // Generate hash value from label name (19-bit, always positive)
                    // Use 19 bits (0x7FFFF) to ensure value is never interpreted as negative
                    int labelHash = labelNameToFind.hashCode() & 0x7FFFF;
                    if (rewritten == null) {
                        rewritten = new ArrayList<>(ops);
                    }
                    rewritten.set(i, new IrTypedImm("LABELREF", labelHash));
                }
            }
        }
        return rewritten != null ? new IrInstruction(instruction.opcode(), rewritten, instruction.source()) : instruction;
    }

    private String qualifyName(String localName, LinkingContext context) {
        String chain = context.currentAliasChain();
        if (chain != null && !chain.isEmpty()) {
            return chain + "." + localName.toUpperCase();
        }
        return localName.toUpperCase();
    }
}
