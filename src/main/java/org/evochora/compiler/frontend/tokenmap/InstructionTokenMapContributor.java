package org.evochora.compiler.frontend.tokenmap;

import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.InstructionNode;

/**
 * Token map contributor for {@link InstructionNode}.
 *
 * <p>Adds CALL and RET opcodes as {@link Symbol.Type#CONSTANT} tokens in the current scope.
 * Other instructions are not added to the token map.</p>
 */
public class InstructionTokenMapContributor implements ITokenMapContributor {

	@Override
	public void contribute(AstNode node, ITokenMapContext context) {
		InstructionNode instructionNode = (InstructionNode) node;
		String opcode = instructionNode.opcode();
		if ("CALL".equalsIgnoreCase(opcode) || "RET".equalsIgnoreCase(opcode)) {
			context.addToken(instructionNode.sourceInfo(), opcode, Symbol.Type.CONSTANT, context.currentScope());
		}
	}
}
