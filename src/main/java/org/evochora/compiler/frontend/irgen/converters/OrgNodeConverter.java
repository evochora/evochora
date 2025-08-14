package org.evochora.compiler.frontend.irgen.converters;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.frontend.parser.ast.VectorLiteralNode;
import org.evochora.compiler.frontend.parser.features.org.OrgNode;
import org.evochora.compiler.ir.IrDirective;
import org.evochora.compiler.ir.IrValue;

import java.util.HashMap;
import java.util.Map;

/**
 * Converts {@link OrgNode} into a generic {@link IrDirective} (namespace "core", name "org").
 */
public final class OrgNodeConverter implements IAstNodeToIrConverter<OrgNode> {

	@Override
	public void convert(OrgNode node, IrGenContext ctx) {
		if (node.originVector() instanceof VectorLiteralNode v) {
			int[] comps = v.components().stream().mapToInt(tok -> Integer.parseInt(tok.text())).toArray();
			Map<String, IrValue> args = new HashMap<>();
			args.put("position", new IrValue.Vector(comps));
			ctx.emit(new IrDirective("core", "org", args, ctx.sourceOf(node)));
		}
	}
}


