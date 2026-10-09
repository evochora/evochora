package org.evochora.compiler.features.label;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.model.ir.IrLabelDef;

/**
 * Converts {@link LabelNode} into {@link IrLabelDef}.
 */
public final class LabelNodeConverter implements IAstNodeToIrConverter<LabelNode> {

    @Override
    public void convert(LabelNode node, IrGenContext ctx) {
        String qualifiedName = ctx.qualifyName(node.name());
        ctx.emit(new IrLabelDef(qualifiedName, ctx.sourceOf(node)));
    }
}
