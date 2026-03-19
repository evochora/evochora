package org.evochora.compiler.frontend.tokenmap;

import org.evochora.compiler.api.TokenKind;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.InstructionNode;

/**
 * Token map contributor for {@link InstructionNode}.
 * Adds every opcode as a {@link TokenKind#INSTRUCTION} token in the current scope.
 */
public class InstructionTokenMapContributor implements ITokenMapContributor {

	@Override
	public void contribute(AstNode node, ITokenMapContext context) {
		InstructionNode instructionNode = (InstructionNode) node;
		context.addToken(instructionNode.sourceInfo(), instructionNode.opcode(), TokenKind.INSTRUCTION, context.currentScope());
	}
}
