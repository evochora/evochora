package org.evochora.compiler.features.control;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.model.ir.IrLabelDef;

/**
 * Converts a {@link ControlCase} into the label of the case, on the level of its block, followed
 * by the case's statements.
 */
public final class ControlCaseConverter implements IAstNodeToIrConverter<ControlCase> {

    @Override
    public void convert(ControlCase node, IrGenContext ctx) {
        ctx.emit(new IrLabelDef(ctx.qualifyName(node.name()), ctx.sourceOf(node)));
        node.statements().forEach(ctx::convert);
    }
}
