package org.evochora.compiler.frontend.irgen.converters;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.features.label.LabelNode;
import org.evochora.compiler.ir.IrLabelDef;

/**
 * Converts {@link LabelNode} into {@link IrLabelDef} and then delegates to the child statement if present.
 */
public final class LabelNodeConverter implements IAstNodeToIrConverter<LabelNode> {

	/**
	 * {@inheritDoc}
	 * <p>
	 * This implementation converts the {@link LabelNode} to an {@link IrLabelDef}
	 * and then recursively converts the labeled statement.
	 *
	 * @param node The node to convert.
	 * @param ctx  The generation context.
	 */
	@Override
	public void convert(LabelNode node, IrGenContext ctx) {
		ctx.emit(new IrLabelDef(node.labelToken().text(), ctx.sourceOf(node)));
		AstNode stmt = node.statement();
		if (stmt != null) {
			ctx.convert(stmt);
		}
	}
}


