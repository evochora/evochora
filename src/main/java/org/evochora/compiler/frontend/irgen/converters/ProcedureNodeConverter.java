package org.evochora.compiler.frontend.irgen.converters;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;
import org.evochora.compiler.ir.IrDirective;
import org.evochora.compiler.ir.IrValue;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts {@link ProcedureNode} into generic enter/exit directives (namespace "core").
 * The body is expected to be present in the AST sequence and handled separately by the generator.
 */
public final class ProcedureNodeConverter implements IAstNodeToIrConverter<ProcedureNode> {

	/**
	 * {@inheritDoc}
	 * <p>
	 * This implementation converts the {@link ProcedureNode} to a sequence of
	 * `proc_enter` directive, the converted body, and a `proc_exit` directive.
	 * It also manages the procedure parameter scope.
	 *
	 * @param node The node to convert.
	 * @param ctx  The generation context.
	 */
	@Override
	public void convert(ProcedureNode node, IrGenContext ctx) {
		// Define a label at the procedure entry so CALL <name> can link to it
		ctx.emit(new org.evochora.compiler.ir.IrLabelDef(node.name().text(), ctx.sourceOf(node)));
		// Install parameter names for this procedure scope so identifiers can resolve to %FPRx
		ctx.pushProcedureParams(node.parameters());
		Map<String, IrValue> enterArgs = new HashMap<>();
		enterArgs.put("name", new IrValue.Str(node.name().text()));
		enterArgs.put("arity", new IrValue.Int64(node.parameters() != null ? node.parameters().size() : 0));
		enterArgs.put("exported", new IrValue.Bool(node.exported()));
		if (node.refParameters() != null) {
			enterArgs.put("refParams", new IrValue.ListVal(node.refParameters().stream().map(t -> new IrValue.Str(t.text())).collect(Collectors.toList())));
		}
		if (node.valParameters() != null) {
			enterArgs.put("valParams", new IrValue.ListVal(node.valParameters().stream().map(t -> new IrValue.Str(t.text())).collect(Collectors.toList())));
		}
		ctx.emit(new IrDirective("core", "proc_enter", enterArgs, ctx.sourceOf(node)));

		// Convert body inline to preserve logical grouping
		node.body().forEach(ctx::convert);

		Map<String, IrValue> exitArgs = new HashMap<>();
		exitArgs.put("name", new IrValue.Str(node.name().text()));
		exitArgs.put("arity", new IrValue.Int64(node.parameters() != null ? node.parameters().size() : 0));
		exitArgs.put("exported", new IrValue.Bool(node.exported()));
		if (node.refParameters() != null) {
			exitArgs.put("refParams", new IrValue.ListVal(node.refParameters().stream().map(t -> new IrValue.Str(t.text())).collect(Collectors.toList())));
		}
		if (node.valParameters() != null) {
			exitArgs.put("valParams", new IrValue.ListVal(node.valParameters().stream().map(t -> new IrValue.Str(t.text())).collect(Collectors.toList())));
		}
		ctx.emit(new IrDirective("core", "proc_exit", exitArgs, ctx.sourceOf(node)));
		ctx.popProcedureParams();
	}
}


