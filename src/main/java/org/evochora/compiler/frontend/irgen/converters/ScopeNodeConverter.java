package org.evochora.compiler.frontend.irgen.converters;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.frontend.parser.features.scope.ScopeNode;
import org.evochora.compiler.ir.IrDirective;
import org.evochora.compiler.ir.IrValue;

import java.util.HashMap;
import java.util.Map;

/**
 * Converts {@link ScopeNode} into generic enter/exit directives (namespace "core").
 */
public final class ScopeNodeConverter implements IAstNodeToIrConverter<ScopeNode> {

	/**
	 * {@inheritDoc}
	 * <p>
	 * This implementation converts the {@link ScopeNode} to a sequence of
	 * `scope_enter` directive, the converted body, and a `scope_exit` directive.
	 *
	 * @param node The node to convert.
	 * @param ctx  The generation context.
	 */
	@Override
	public void convert(ScopeNode node, IrGenContext ctx) {
		Map<String, IrValue> enter = new HashMap<>();
		enter.put("name", new IrValue.Str(node.name().text()));
		ctx.emit(new IrDirective("core", "scope_enter", enter, ctx.sourceOf(node)));

		// Convert scope body inline
		node.body().forEach(ctx::convert);

		Map<String, IrValue> exit = new HashMap<>();
		exit.put("name", new IrValue.Str(node.name().text()));
		ctx.emit(new IrDirective("core", "scope_exit", exit, ctx.sourceOf(node)));
	}
}


