package org.evochora.compiler.frontend.irgen.converters;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.InstructionNode;
import org.evochora.compiler.model.ast.NumberLiteralNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.model.ast.TypedLiteralNode;
import org.evochora.compiler.model.ast.VectorLiteralNode;
import org.evochora.compiler.model.ir.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts {@link InstructionNode} into an {@link IrInstruction} with typed operands.
 */
public final class InstructionNodeConverter implements IAstNodeToIrConverter<InstructionNode> {

    /**
     * {@inheritDoc}
     * <p>
     * This implementation converts the {@link InstructionNode} to an {@link IrInstruction}.
     * It also handles the special case of `CALL ... WITH ...` by emitting a `core.call_with` directive.
     *
     * @param node The node to convert.
     * @param ctx  The generation context.
     */
    @Override
    public void convert(InstructionNode node, IrGenContext ctx) {
        String opcode = node.opcode();

        // New CALL syntax with REF and VAL
        if ("CALL".equalsIgnoreCase(opcode) && (!node.refArguments().isEmpty() || !node.valArguments().isEmpty())) {
            List<IrOperand> operands = new ArrayList<>();
            // The first argument is the procedure name
            if (!node.arguments().isEmpty()) {
                operands.add(convertOperand(node.arguments().get(0), ctx));
            }

            List<IrOperand> refOperands = node.refArguments().stream()
                .map(arg -> convertOperand(arg, ctx))
                .toList();

            List<IrOperand> valOperands = node.valArguments().stream()
                .map(arg -> convertOperand(arg, ctx))
                .toList();

            ctx.emit(new IrInstruction(opcode, operands, refOperands, valOperands, ctx.sourceOf(node)));
            return;
        }

        // Legacy CALL syntax and other instructions
        List<IrOperand> operands = new ArrayList<>();
        int withIdx = -1;

        if ("CALL".equalsIgnoreCase(opcode)) {
            for (int i = 0; i < node.arguments().size(); i++) {
                AstNode a = node.arguments().get(i);
                if (a instanceof IdentifierNode id) {
                    String t = id.text().toUpperCase();
                    if ("WITH".equals(t) || ".WITH".equals(t)) {
                        withIdx = i;
                        break;
                    }
                }
            }
        }

        int end = withIdx >= 0 ? withIdx : node.arguments().size();
        for (int i = 0; i < end; i++) {
            operands.add(convertOperand(node.arguments().get(i), ctx));
        }

        if (withIdx >= 0) {
            java.util.Map<String, IrValue> args = new java.util.HashMap<>();
            java.util.List<IrValue> actuals = new java.util.ArrayList<>();
            for (int j = withIdx + 1; j < node.arguments().size(); j++) {
                AstNode a = node.arguments().get(j);
                if (a instanceof RegisterNode r) {
                    actuals.add(new IrValue.Str(r.getName()));
                } else if (a instanceof IdentifierNode id) {
                    String nameU = id.text().toUpperCase();
                    java.util.Optional<Integer> idxOpt = ctx.resolveProcedureParam(nameU);
                    if (idxOpt.isPresent()) {
                        actuals.add(new IrValue.Str("%FPR" + idxOpt.get()));
                    }
                }
            }
            args.put("actuals", new IrValue.ListVal(actuals));
            ctx.emit(new IrDirective("core", "call_with", args, ctx.sourceOf(node)));
        }

        ctx.emit(new IrInstruction(opcode, operands, ctx.sourceOf(node)));
    }

    /**
     * Converts an AST node for an instruction argument into an {@link IrOperand}.
     * This involves resolving identifiers as constants, procedure parameters, or labels.
     *
     * @param arg The AST node of the argument.
     * @param ctx The generation context for resolving symbols.
     * @return The corresponding {@link IrOperand}.
     */
    private IrOperand convertOperand(AstNode arg, IrGenContext ctx) {
        if (arg instanceof RegisterNode r) {
            return new IrReg(r.getName());
        } else if (arg instanceof NumberLiteralNode n) {
            return new IrImm(n.value());
        } else if (arg instanceof TypedLiteralNode t) {
            return new IrTypedImm(t.typeName(), t.value());
        } else if (arg instanceof VectorLiteralNode v) {
            int[] comps = v.values().stream().mapToInt(Integer::intValue).toArray();
            return new IrVec(comps);
        } else if (arg instanceof IdentifierNode id) {
            String nameU = id.text().toUpperCase();
            java.util.Optional<Integer> idxOpt = ctx.resolveProcedureParam(nameU);
            if (idxOpt.isPresent()) {
                return new IrReg("%FPR" + idxOpt.get());
            }
            java.util.Optional<IrOperand> constOpt = ctx.resolveConstant(nameU, id.sourceInfo().fileName());
            if (constOpt.isPresent()) {
                return constOpt.get();
            }
            return new IrLabelRef(id.text());
        }
        return new IrLabelRef(arg.toString());
    }

}