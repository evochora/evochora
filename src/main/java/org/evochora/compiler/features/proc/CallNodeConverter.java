package org.evochora.compiler.features.proc;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrInstruction;
import org.evochora.compiler.model.ir.IrOperand;
import org.evochora.compiler.model.ir.IrValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Converts {@link CallNode} into IR instructions for CALL semantics.
 * Handles both new syntax (REF/VAL) producing {@link IrCallInstruction},
 * and legacy syntax (WITH) producing an {@link IrDirective} + {@link IrInstruction}.
 */
public final class CallNodeConverter implements IAstNodeToIrConverter<CallNode> {

    @Override
    public void convert(CallNode node, IrGenContext ctx) {
        // New syntax: REF/VAL arguments → IrCallInstruction
        if (!node.refArguments().isEmpty() || !node.valArguments().isEmpty()) {
            convertNewSyntax(node, ctx);
        } else {
            convertLegacySyntax(node, ctx);
        }
    }

    private void convertNewSyntax(CallNode node, IrGenContext ctx) {
        List<IrOperand> operands = new ArrayList<>();
        operands.add(ctx.convertOperand(node.procedureName()));

        List<IrOperand> refOperands = node.refArguments().stream()
                .map(ctx::convertOperand)
                .toList();

        List<IrOperand> valOperands = node.valArguments().stream()
                .map(ctx::convertOperand)
                .toList();

        ctx.emit(new IrCallInstruction("CALL", operands, refOperands, valOperands, ctx.sourceOf(node)));
    }

    private void convertLegacySyntax(CallNode node, IrGenContext ctx) {
        List<IrOperand> operands = new ArrayList<>();
        operands.add(ctx.convertOperand(node.procedureName()));

        // Find WITH keyword in legacy arguments
        int withIdx = -1;
        for (int i = 0; i < node.legacyArguments().size(); i++) {
            AstNode a = node.legacyArguments().get(i);
            if (a instanceof IdentifierNode id) {
                String t = id.text().toUpperCase();
                if ("WITH".equals(t) || ".WITH".equals(t)) {
                    withIdx = i;
                    break;
                }
            }
        }

        // Arguments before WITH (if any) are additional operands
        int end = withIdx >= 0 ? withIdx : node.legacyArguments().size();
        for (int i = 0; i < end; i++) {
            operands.add(ctx.convertOperand(node.legacyArguments().get(i)));
        }

        // WITH actuals → core:call_with directive
        if (withIdx >= 0) {
            Map<String, IrValue> args = new HashMap<>();
            List<IrValue> actuals = new ArrayList<>();
            for (int j = withIdx + 1; j < node.legacyArguments().size(); j++) {
                AstNode a = node.legacyArguments().get(j);
                if (a instanceof RegisterNode r) {
                    actuals.add(new IrValue.Str(r.getName()));
                } else if (a instanceof IdentifierNode id) {
                    String nameU = id.text().toUpperCase();
                    Optional<Integer> idxOpt = ctx.resolveProcedureParam(nameU);
                    if (idxOpt.isPresent()) {
                        actuals.add(new IrValue.Str("%FPR" + idxOpt.get()));
                    }
                }
            }
            args.put("actuals", new IrValue.ListVal(actuals));
            ctx.emit(new IrDirective("core", "call_with", args, ctx.sourceOf(node)));
        }

        ctx.emit(new IrCallInstruction("CALL", operands, List.of(), List.of(), ctx.sourceOf(node)));
    }
}
