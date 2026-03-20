package org.evochora.compiler.features.define;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.model.ir.IrOperand;

/**
 * Captures constants from .DEFINE into the IR generation context so they can be
 * resolved during instruction operand conversion.
 */
public final class DefineNodeConverter implements IAstNodeToIrConverter<DefineNode> {

    /**
     * {@inheritDoc}
     * <p>
     * This implementation registers the constant in the {@link IrGenContext} for later use.
     * It does not emit any {@link org.evochora.compiler.model.ir.IrItem}s.
     *
     * @param node The node to convert.
     * @param ctx  The generation context.
     */
    @Override
    public void convert(DefineNode node, IrGenContext ctx) {
        String nameUpper = node.name().toUpperCase();
        IrOperand value = ctx.convertOperand(node.value());
        ctx.registerConstant(nameUpper, value);
    }
}
