package org.evochora.compiler.features.instruction;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.model.ast.InstructionNode;
import org.evochora.compiler.model.ir.IrInstruction;
import org.evochora.compiler.model.ir.IrOperand;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts {@link InstructionNode} into an {@link IrInstruction} with typed operands.
 */
public final class InstructionNodeConverter implements IAstNodeToIrConverter<InstructionNode> {

    @Override
    public void convert(InstructionNode node, IrGenContext ctx) {
        List<IrOperand> operands = new ArrayList<>();
        for (var arg : node.arguments()) {
            operands.add(ctx.convertOperand(arg));
        }
        ctx.emit(new IrInstruction(node.opcode(), operands, ctx.sourceOf(node)));
    }
}
