package org.evochora.compiler.frontend.parser.features.proc;

import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.SourceLocatable;

import java.util.List;

/**
 * An AST node that represents a procedure definition (<code>.proc</code> ... <code>.endp</code>).
 *
 * @param name The token containing the name of the procedure.
 * @param exported Whether the procedure is exported (visibility for other modules).
 * @param parameters A list of tokens representing the parameters of the procedure (from <code>.with</code>).
 * @param refParameters A list of tokens representing the reference parameters of the procedure (from <code>REF</code>).
 * @param valParameters A list of tokens representing the value parameters of the procedure (from <code>VAL</code>).
 * @param body A list of AST nodes representing the content of the procedure.
 */
public record ProcedureNode(
        Token name,
        boolean exported,
        List<Token> parameters,
        List<Token> refParameters,
        List<Token> valParameters,
        List<AstNode> body
) implements AstNode, SourceLocatable {

    @Override
    public String getSourceFileName() {
        return name.fileName();
    }

    @Override
    public List<AstNode> getChildren() {
        // The children of a procedure are all the statements in its body.
        return body;
    }

    @Override
    public AstNode reconstructWithChildren(List<AstNode> newChildren) {
        // Create a new ProcedureNode with the new children (body)
        return new ProcedureNode(name, exported, parameters, refParameters, valParameters, newChildren);
    }
}