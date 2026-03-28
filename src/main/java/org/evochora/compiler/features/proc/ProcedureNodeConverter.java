package org.evochora.compiler.features.proc;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;

import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts {@link ProcedureNode} into generic enter/exit directives (namespace "core").
 * Parameter identifiers are already resolved to RegisterNodes in Phase 6 by the
 * AstPostProcessor via SymbolTable scope-aware lookup.
 */
public final class ProcedureNodeConverter implements IAstNodeToIrConverter<ProcedureNode> {

	@Override
	public void convert(ProcedureNode node, IrGenContext ctx) {
		String qualifiedName = ctx.qualifyName(node.name());
		ctx.emit(new org.evochora.compiler.model.ir.IrLabelDef(qualifiedName, ctx.sourceOf(node)));

		int lrefArity = node.lrefParameters() != null ? node.lrefParameters().size() : 0;
		int lvalArity = node.lvalParameters() != null ? node.lvalParameters().size() : 0;

		Map<String, IrValue> enterArgs = buildProcArgs(node, qualifiedName, lrefArity, lvalArity);
		ctx.emit(new IrDirective("core", "proc_enter", enterArgs, ctx.sourceOf(node)));

		node.body().forEach(ctx::convert);

		Map<String, IrValue> exitArgs = buildProcArgs(node, qualifiedName, lrefArity, lvalArity);
		ctx.emit(new IrDirective("core", "proc_exit", exitArgs, ctx.sourceOf(node)));
	}

	private Map<String, IrValue> buildProcArgs(ProcedureNode node, String qualifiedName, int lrefArity, int lvalArity) {
		Map<String, IrValue> args = new HashMap<>();
		args.put("name", new IrValue.Str(qualifiedName));
		args.put("exported", new IrValue.Bool(node.exported()));
		if (node.refParameters() != null) {
			args.put("refParams", new IrValue.ListVal(node.refParameters().stream().map(p -> new IrValue.Str(p.name())).collect(Collectors.toList())));
		}
		if (node.valParameters() != null) {
			args.put("valParams", new IrValue.ListVal(node.valParameters().stream().map(p -> new IrValue.Str(p.name())).collect(Collectors.toList())));
		}
		if (node.lrefParameters() != null) {
			args.put("lrefParams", new IrValue.ListVal(node.lrefParameters().stream().map(p -> new IrValue.Str(p.name())).collect(Collectors.toList())));
		}
		if (node.lvalParameters() != null) {
			args.put("lvalParams", new IrValue.ListVal(node.lvalParameters().stream().map(p -> new IrValue.Str(p.name())).collect(Collectors.toList())));
		}
		args.put("lrefArity", new IrValue.Int64(lrefArity));
		args.put("lvalArity", new IrValue.Int64(lvalArity));
		return args;
	}
}
