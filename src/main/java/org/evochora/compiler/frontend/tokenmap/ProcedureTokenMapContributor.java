package org.evochora.compiler.frontend.tokenmap;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.token.Token;

import java.util.List;

/**
 * Token map contributor for {@link ProcedureNode}.
 *
 * <p>Adds the procedure name as a {@link Symbol.Type#PROCEDURE} token in global scope,
 * and all formal parameters as {@link Symbol.Type#VARIABLE} tokens in the procedure's scope.</p>
 */
public class ProcedureTokenMapContributor implements ITokenMapContributor {

	@Override
	public void contribute(AstNode node, ITokenMapContext context) {
		ProcedureNode procNode = (ProcedureNode) node;

		context.addToken(
			new SourceInfo(procNode.name().fileName(), procNode.name().line(), procNode.name().column()),
			procNode.name().text(), Symbol.Type.PROCEDURE, "global");

		String procScope = context.currentScope();
		addParameters(procNode.parameters(), procScope, context);
		addParameters(procNode.refParameters(), procScope, context);
		addParameters(procNode.valParameters(), procScope, context);
	}

	private void addParameters(List<Token> params, String scope, ITokenMapContext context) {
		if (params == null) return;
		for (Token param : params) {
			context.addToken(
				new SourceInfo(param.fileName(), param.line(), param.column()),
				param.text(), Symbol.Type.VARIABLE, scope);
		}
	}
}
