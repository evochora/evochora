package org.evochora.compiler.features.org;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts {@link OrgNode} into a generic {@link IrDirective} (namespace "core", name "org").
 */
public final class OrgNodeConverter implements IAstNodeToIrConverter<OrgNode> {

	/**
	 * {@inheritDoc}
	 * <p>
	 * This implementation converts the {@link OrgNode} to an {@link IrDirective}. The position
	 * carries the components, the {@code relative} list one flag per component.
	 *
	 * @param node The node to convert.
	 * @param ctx  The generation context.
	 */
	@Override
	public void convert(OrgNode node, IrGenContext ctx) {
		int[] comps = node.originVector().values().stream().mapToInt(Integer::intValue).toArray();
		List<IrValue> relative = new ArrayList<>(node.relative().size());
		for (boolean marked : node.relative()) {
			relative.add(new IrValue.Bool(marked));
		}
		Map<String, IrValue> args = new HashMap<>();
		args.put("position", new IrValue.Vector(comps));
		args.put("relative", new IrValue.ListVal(List.copyOf(relative)));
		ctx.emit(new IrDirective("core", "org", args, ctx.sourceOf(node)));
	}
}
