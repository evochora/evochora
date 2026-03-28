package org.evochora.compiler.features.proc;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.model.ir.IrOperand;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts {@link CallNode} into IR instructions for CALL semantics.
 * Produces {@link IrCallInstruction} with REF/VAL/LREF/LVAL operand lists.
 */
public final class CallNodeConverter implements IAstNodeToIrConverter<CallNode> {

    @Override
    public void convert(CallNode node, IrGenContext ctx) {
        List<IrOperand> operands = new ArrayList<>();
        operands.add(ctx.convertOperand(node.procedureName()));

        List<IrOperand> refOperands = node.refArguments().stream()
                .map(ctx::convertOperand)
                .toList();

        List<IrOperand> valOperands = node.valArguments().stream()
                .map(ctx::convertOperand)
                .toList();
        List<IrOperand> lrefOperands = node.lrefArguments().stream()
                .map(ctx::convertOperand)
                .toList();
        List<IrOperand> lvalOperands = node.lvalArguments().stream()
                .map(ctx::convertOperand)
                .toList();

        ctx.emit(new IrCallInstruction("CALL", operands, refOperands, valOperands, lrefOperands, lvalOperands, ctx.sourceOf(node)));
    }
}
