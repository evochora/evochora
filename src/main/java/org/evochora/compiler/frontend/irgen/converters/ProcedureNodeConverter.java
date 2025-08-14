package org.evochora.compiler.frontend.irgen.converters;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;
import org.evochora.compiler.ir.IrDirective;
import org.evochora.compiler.ir.IrValue;

import java.util.HashMap;
import java.util.Map;

/**
 * Converts {@link ProcedureNode} into generic enter/exit directives (namespace "core").
 * The body is expected to be present in the AST sequence and handled separately by the generator.
 */
public final class ProcedureNodeConverter implements IAstNodeToIrConverter<ProcedureNode> {

	@Override
	public void convert(ProcedureNode node, IrGenContext ctx) {
		Map<String, IrValue> enterArgs = new HashMap<>();
		enterArgs.put("name", new IrValue.Str(node.name().text()));
		enterArgs.put("arity", new IrValue.Int64(node.parameters() != null ? node.parameters().size() : 0));
		ctx.emit(new IrDirective("core", "proc_enter", enterArgs, ctx.sourceOf(node)));

		// Convert body inline to preserve logical grouping
		node.body().forEach(ctx::convert);

		Map<String, IrValue> exitArgs = new HashMap<>();
		exitArgs.put("name", new IrValue.Str(node.name().text()));
		exitArgs.put("arity", new IrValue.Int64(node.parameters() != null ? node.parameters().size() : 0));
		ctx.emit(new IrDirective("core", "proc_exit", exitArgs, ctx.sourceOf(node)));
	}
}


