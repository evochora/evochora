package org.evochora.compiler.features.dir;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;

import java.util.HashMap;
import java.util.Map;

/**
 * Converts {@link DirNode} into a generic {@link IrDirective} (namespace "core", name "dir").
 */
public final class DirNodeConverter implements IAstNodeToIrConverter<DirNode> {

	/**
	 * {@inheritDoc}
	 * <p>
	 * This implementation converts the {@link DirNode} to an {@link IrDirective}. A written-out
	 * direction carries a {@code direction} vector, a rotation the plane and the way round.
	 *
	 * @param node The node to convert.
	 * @param ctx  The generation context.
	 */
	@Override
	public void convert(DirNode node, IrGenContext ctx) {
		Map<String, IrValue> args = new HashMap<>();
		switch (node.mode()) {
			case DirNode.Mode.Absolute absolute -> {
				int[] comps = absolute.vector().values().stream().mapToInt(Integer::intValue).toArray();
				args.put("direction", new IrValue.Vector(comps));
			}
			case DirNode.Mode.Rotation rotation -> {
				args.put("forward", new IrValue.Bool(rotation.forward()));
				args.put("axisA", new IrValue.Int64(rotation.axisA()));
				args.put("axisB", new IrValue.Int64(rotation.axisB()));
			}
		}
		ctx.emit(new IrDirective("core", "dir", args, ctx.sourceOf(node)));
	}
}
