package org.evochora.compiler.features.define;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;

/**
 * Converts a {@link DefineNode} into nothing: a constant has no representation in the IR.
 * Every reference to it was replaced by its value in the post-processing phase, and the
 * definition itself places no code. The converter exists so that the node is accepted by
 * IR generation instead of being reported as unknown.
 */
public final class DefineNodeConverter implements IAstNodeToIrConverter<DefineNode> {

    @Override
    public void convert(DefineNode node, IrGenContext ctx) {
        // A constant emits no IR item.
    }
}
