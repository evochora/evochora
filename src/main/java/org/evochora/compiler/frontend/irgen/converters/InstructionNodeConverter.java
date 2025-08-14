package org.evochora.compiler.frontend.irgen.converters;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.IdentifierNode;
import org.evochora.compiler.frontend.parser.ast.InstructionNode;
import org.evochora.compiler.frontend.parser.ast.NumberLiteralNode;
import org.evochora.compiler.frontend.parser.ast.RegisterNode;
import org.evochora.compiler.frontend.parser.ast.TypedLiteralNode;
import org.evochora.compiler.frontend.parser.ast.VectorLiteralNode;
import org.evochora.compiler.ir.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts {@link InstructionNode} into an {@link IrInstruction} with typed operands.
 */
public final class InstructionNodeConverter implements IAstNodeToIrConverter<InstructionNode> {

	@Override
	public void convert(InstructionNode node, IrGenContext ctx) {
		List<IrOperand> operands = new ArrayList<>();
		// Sonderfall CALL WITH: trenne Operanden vom WITH-Block und emittiere core:call_with davor
		String opcode = node.opcode().text();
		int withIdx = -1;
		if ("CALL".equalsIgnoreCase(opcode)) {
			for (int i = 0; i < node.arguments().size(); i++) {
				AstNode a = node.arguments().get(i);
				if (a instanceof IdentifierNode id) {
					String t = id.identifierToken().text().toUpperCase();
					if ("WITH".equals(t) || ".WITH".equals(t)) { withIdx = i; break; }
				}
			}
		}

		int end = withIdx >= 0 ? withIdx : node.arguments().size();
		for (int i = 0; i < end; i++) operands.add(convertOperand(node.arguments().get(i)));

		if (withIdx >= 0) {
			java.util.Map<String, IrValue> args = new java.util.HashMap<>();
			java.util.List<IrValue> actuals = new java.util.ArrayList<>();
			for (int j = withIdx + 1; j < node.arguments().size(); j++) {
				AstNode a = node.arguments().get(j);
				if (a instanceof RegisterNode r) actuals.add(new IrValue.Str(r.registerToken().text()));
			}
			args.put("actuals", new IrValue.ListVal(actuals));
			ctx.emit(new IrDirective("core", "call_with", args, ctx.sourceOf(node)));
		}

		ctx.emit(new IrInstruction(opcode, operands, ctx.sourceOf(node)));
	}

	private IrOperand convertOperand(AstNode arg) {
		if (arg instanceof RegisterNode r) {
			return new IrReg(r.registerToken().text());
		} else if (arg instanceof NumberLiteralNode n) {
			return new IrImm(n.getValue());
		} else if (arg instanceof TypedLiteralNode t) {
			return new IrTypedImm(t.type().text(), Integer.parseInt(t.value().text()));
		} else if (arg instanceof VectorLiteralNode v) {
			int[] comps = v.components().stream().mapToInt(tok -> Integer.parseInt(tok.text())).toArray();
			return new IrVec(comps);
		} else if (arg instanceof IdentifierNode id) {
			return new IrLabelRef(id.identifierToken().text());
		}
		// Fallback: treat as label-like reference for now
		return new IrLabelRef(arg.toString());
	}
}


