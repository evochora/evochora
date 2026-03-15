package org.evochora.compiler.frontend.irgen.converters;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.InstructionNode;
import org.evochora.compiler.model.ast.RegisterNode;
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
                operands.add(ctx.convertOperand(node.arguments().get(0)));
            }

            List<IrOperand> refOperands = node.refArguments().stream()
                .map(arg -> ctx.convertOperand(arg))
                .toList();

            List<IrOperand> valOperands = node.valArguments().stream()
                .map(arg -> ctx.convertOperand(arg))
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
            operands.add(ctx.convertOperand(node.arguments().get(i)));
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

}