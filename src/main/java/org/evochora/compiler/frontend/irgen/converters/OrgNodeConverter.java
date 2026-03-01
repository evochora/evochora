package org.evochora.compiler.frontend.irgen.converters;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.model.ast.VectorLiteralNode;
import org.evochora.compiler.frontend.parser.features.org.OrgNode;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;

import java.util.HashMap;
import java.util.Map;

/**
 * Converts {@link OrgNode} into a generic {@link IrDirective} (namespace "core", name "org").
 */
public final class OrgNodeConverter implements IAstNodeToIrConverter<OrgNode> {

	/**
	 * {@inheritDoc}
	 * <p>
	 * This implementation converts the {@link OrgNode} to an {@link IrDirective}.
	 *
	 * @param node The node to convert.
	 * @param ctx  The generation context.
	 */
	@Override
	public void convert(OrgNode node, IrGenContext ctx) {
		if (node.originVector() instanceof VectorLiteralNode v) {
			int[] comps = v.values().stream().mapToInt(Integer::intValue).toArray();
			Map<String, IrValue> args = new HashMap<>();
			args.put("position", new IrValue.Vector(comps));
			ctx.emit(new IrDirective("core", "org", args, ctx.sourceOf(node)));
		}
	}
}


