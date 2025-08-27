package org.evochora.compiler.frontend.irgen.converters;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.IdentifierNode;
import org.evochora.compiler.frontend.parser.ast.NumberLiteralNode;
import org.evochora.compiler.frontend.parser.ast.TypedLiteralNode;
import org.evochora.compiler.frontend.parser.ast.VectorLiteralNode;
import org.evochora.compiler.frontend.parser.features.def.DefineNode;
import org.evochora.compiler.ir.IrImm;
import org.evochora.compiler.ir.IrOperand;
import org.evochora.compiler.ir.IrTypedImm;
import org.evochora.compiler.ir.IrVec;

/**
 * Captures constants from .DEFINE into the IR generation context so they can be
 * resolved during instruction operand conversion.
 */
public final class DefineNodeConverter implements IAstNodeToIrConverter<DefineNode> {

    /**
     * {@inheritDoc}
     * <p>
     * This implementation registers the constant in the {@link IrGenContext} for later use.
     * It does not emit any {@link org.evochora.compiler.ir.IrItem}s.
     *
     * @param node The node to convert.
     * @param ctx  The generation context.
     */
    @Override
    public void convert(DefineNode node, IrGenContext ctx) {
        String nameUpper = node.name().text().toUpperCase();
        IrOperand value = toOperand(node.value());
        if (value != null) {
            ctx.registerConstant(nameUpper, value);
        } else if (node.value() instanceof IdentifierNode id) {
            // Allow constant aliasing: resolve later as label if needed
            ctx.registerConstant(nameUpper, new org.evochora.compiler.ir.IrLabelRef(id.identifierToken().text()));
        }
        // .DEFINE does not emit IR by itself
    }

    /**
     * Converts an AST node representing a literal value into an {@link IrOperand}.
     *
     * @param value The AST node to convert.
     * @return The corresponding {@link IrOperand}, or {@code null} if the node type is not a literal.
     */
    private IrOperand toOperand(AstNode value) {
        if (value instanceof NumberLiteralNode n) {
            return new IrImm(n.getValue());
        }
        if (value instanceof TypedLiteralNode t) {
            return new IrTypedImm(t.type().text(), Integer.parseInt(t.value().text()));
        }
        if (value instanceof VectorLiteralNode v) {
            int[] comps = v.components().stream().mapToInt(tok -> Integer.parseInt(tok.text())).toArray();
            return new IrVec(comps);
        }
        return null;
    }
}


