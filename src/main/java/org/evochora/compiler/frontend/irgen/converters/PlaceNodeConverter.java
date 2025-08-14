package org.evochora.compiler.frontend.irgen.converters;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.frontend.parser.ast.NumberLiteralNode;
import org.evochora.compiler.frontend.parser.ast.TypedLiteralNode;
import org.evochora.compiler.frontend.parser.ast.VectorLiteralNode;
import org.evochora.compiler.frontend.parser.features.place.PlaceNode;
import org.evochora.compiler.ir.IrDirective;
import org.evochora.compiler.ir.IrValue;

import java.util.HashMap;
import java.util.Map;

/**
 * Converts {@link PlaceNode} into a generic {@link IrDirective} (namespace "core", name "place").
 */
public final class PlaceNodeConverter implements IAstNodeToIrConverter<PlaceNode> {

	@Override
	public void convert(PlaceNode node, IrGenContext ctx) {
		Map<String, IrValue> args = new HashMap<>();
		if (node.literal() instanceof TypedLiteralNode t) {
			args.put("type", new IrValue.Str(t.type().text()));
			args.put("value", new IrValue.Int64(Long.parseLong(t.value().text())));
		} else if (node.literal() instanceof NumberLiteralNode n) {
			args.put("value", new IrValue.Int64(n.getValue()));
		}
		if (node.position() instanceof VectorLiteralNode v) {
			int[] comps = v.components().stream().mapToInt(tok -> Integer.parseInt(tok.text())).toArray();
			args.put("position", new IrValue.Vector(comps));
		}
		ctx.emit(new IrDirective("core", "place", args, ctx.sourceOf(node)));
	}
}


