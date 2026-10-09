package org.evochora.compiler.features.control;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.model.ir.IrLabelDef;

/**
 * Converts a {@link ControlNode} into the labels of the block and the code between them. The
 * block's name is a label at its start, on the level around the block; its statements and its
 * cases follow inside the block's level, and the label {@code END} stands behind them, at the
 * position of the {@code .ENDCONTROL}. A block adds no instruction and no directive: the code it
 * compiles to is the code of the same program with ordinary labels in place of its words.
 */
public final class ControlNodeConverter implements IAstNodeToIrConverter<ControlNode> {

    @Override
    public void convert(ControlNode node, IrGenContext ctx) {
        ctx.emit(new IrLabelDef(ctx.qualifyName(node.name()), ctx.sourceOf(node)));
        ctx.enterScope(node.name());
        node.statements().forEach(ctx::convert);
        node.cases().forEach(ctx::convert);
        ctx.emit(new IrLabelDef(ctx.qualifyName("END"), node.endSourceInfo()));
        ctx.leaveScope();
    }
}
