package org.evochora.compiler.features.proc;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;

import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts {@link ProcedureNode} into generic enter/exit directives (namespace "core").
 * The body is expected to be present in the AST sequence and handled separately by the generator.
 */
public final class ProcedureNodeConverter implements IAstNodeToIrConverter<ProcedureNode> {

	@Override
	public void convert(ProcedureNode node, IrGenContext ctx) {
		String qualifiedName = ctx.qualifyName(node.name());
		ctx.emit(new org.evochora.compiler.model.ir.IrLabelDef(qualifiedName, ctx.sourceOf(node)));

		List<String> allParams = new ArrayList<>();
		if (node.parameters() != null) {
			node.parameters().stream().map(ProcedureNode.ParamDecl::name).forEach(allParams::add);
		}
		if (node.refParameters() != null) {
			node.refParameters().stream().map(ProcedureNode.ParamDecl::name).forEach(allParams::add);
		}
		if (node.valParameters() != null) {
			node.valParameters().stream().map(ProcedureNode.ParamDecl::name).forEach(allParams::add);
		}
		ctx.pushProcedureParams(allParams);

		Map<String, IrValue> enterArgs = new HashMap<>();
		enterArgs.put("name", new IrValue.Str(qualifiedName));
		enterArgs.put("arity", new IrValue.Int64(node.parameters() != null ? node.parameters().size() : 0));
		enterArgs.put("exported", new IrValue.Bool(node.exported()));
		if (node.refParameters() != null) {
			enterArgs.put("refParams", new IrValue.ListVal(node.refParameters().stream().map(p -> new IrValue.Str(p.name())).collect(Collectors.toList())));
		}
		if (node.valParameters() != null) {
			enterArgs.put("valParams", new IrValue.ListVal(node.valParameters().stream().map(p -> new IrValue.Str(p.name())).collect(Collectors.toList())));
		}
		ctx.emit(new IrDirective("core", "proc_enter", enterArgs, ctx.sourceOf(node)));

		node.body().forEach(ctx::convert);

		Map<String, IrValue> exitArgs = new HashMap<>();
		exitArgs.put("name", new IrValue.Str(qualifiedName));
		exitArgs.put("arity", new IrValue.Int64(node.parameters() != null ? node.parameters().size() : 0));
		exitArgs.put("exported", new IrValue.Bool(node.exported()));
		if (node.refParameters() != null) {
			exitArgs.put("refParams", new IrValue.ListVal(node.refParameters().stream().map(p -> new IrValue.Str(p.name())).collect(Collectors.toList())));
		}
		if (node.valParameters() != null) {
			exitArgs.put("valParams", new IrValue.ListVal(node.valParameters().stream().map(p -> new IrValue.Str(p.name())).collect(Collectors.toList())));
		}
		ctx.emit(new IrDirective("core", "proc_exit", exitArgs, ctx.sourceOf(node)));
		ctx.popProcedureParams();
	}
}
