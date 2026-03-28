package org.evochora.compiler.features.proc;

import org.evochora.compiler.api.TokenKind;
import org.evochora.compiler.frontend.tokenmap.ITokenMapContext;
import org.evochora.compiler.frontend.tokenmap.ITokenMapContributor;

import org.evochora.compiler.model.ast.AstNode;

import java.util.List;

/**
 * Token map contributor for {@link ProcedureNode}.
 *
 * <p>Adds the procedure name as a {@link TokenKind#PROCEDURE} token in global scope,
 * and all formal parameters as {@link TokenKind#PARAMETER} tokens in the procedure's scope.</p>
 */
public class ProcedureTokenMapContributor implements ITokenMapContributor {

	@Override
	public void contribute(AstNode node, ITokenMapContext context) {
		ProcedureNode procNode = (ProcedureNode) node;

		String qualifiedName = context.currentScope();
		context.addToken(
			procNode.sourceInfo(),
			procNode.name(), TokenKind.PROCEDURE, "global", qualifiedName);

		String procScope = context.currentScope();
		addParameters(procNode.refParameters(), procScope, context);
		addParameters(procNode.valParameters(), procScope, context);
		addParameters(procNode.lrefParameters(), procScope, context);
		addParameters(procNode.lvalParameters(), procScope, context);
	}

	private void addParameters(List<ProcedureNode.ParamDecl> params, String scope, ITokenMapContext context) {
		if (params == null) return;
		for (ProcedureNode.ParamDecl param : params) {
			context.addToken(
				param.sourceInfo(),
				param.name(), TokenKind.PARAMETER, scope);
		}
	}
}
