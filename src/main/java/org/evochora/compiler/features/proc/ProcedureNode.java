package org.evochora.compiler.features.proc;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.ISourceLocatable;

import java.util.List;

/**
 * An AST node that represents a procedure definition (<code>.PROC</code> ... <code>.ENDP</code>).
 *
 * @param name The procedure name.
 * @param exported Whether the procedure is exported (visibility for other modules).
 * @param parameters Parameters from old-style <code>WITH</code> syntax.
 * @param refParameters Reference parameters from <code>REF</code> keyword.
 * @param valParameters Value parameters from <code>VAL</code> keyword.
 * @param body The statements inside the procedure block.
 * @param sourceInfo Source location of the procedure name.
 */
public record ProcedureNode(
        String name,
        boolean exported,
        List<ParamDecl> parameters,
        List<ParamDecl> refParameters,
        List<ParamDecl> valParameters,
        List<AstNode> body,
        SourceInfo sourceInfo
) implements AstNode, ISourceLocatable {

    /**
     * A single parameter declaration with its name and source location.
     */
    public record ParamDecl(String name, SourceInfo sourceInfo) {}

    @Override
    public List<AstNode> getChildren() {
        return body;
    }

    @Override
    public AstNode reconstructWithChildren(List<AstNode> newChildren) {
        return new ProcedureNode(name, exported, parameters, refParameters, valParameters, newChildren, sourceInfo);
    }
}
