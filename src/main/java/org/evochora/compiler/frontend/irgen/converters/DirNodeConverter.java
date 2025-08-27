package org.evochora.compiler.frontend.irgen.converters;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.frontend.parser.ast.VectorLiteralNode;
import org.evochora.compiler.frontend.parser.features.dir.DirNode;
import org.evochora.compiler.ir.IrDirective;
import org.evochora.compiler.ir.IrValue;

import java.util.HashMap;
import java.util.Map;

/**
 * Converts {@link DirNode} into a generic {@link IrDirective} (namespace "core", name "dir").
 */
public final class DirNodeConverter implements IAstNodeToIrConverter<DirNode> {

	/**
	 * {@inheritDoc}
	 * <p>
	 * This implementation converts the {@link DirNode} to an {@link IrDirective}.
	 *
	 * @param node The node to convert.
	 * @param ctx  The generation context.
	 */
	@Override
	public void convert(DirNode node, IrGenContext ctx) {
		if (node.directionVector() instanceof VectorLiteralNode v) {
			int[] comps = v.components().stream().mapToInt(tok -> Integer.parseInt(tok.text())).toArray();
			Map<String, IrValue> args = new HashMap<>();
			args.put("direction", new IrValue.Vector(comps));
			ctx.emit(new IrDirective("core", "dir", args, ctx.sourceOf(node)));
		}
	}
}


