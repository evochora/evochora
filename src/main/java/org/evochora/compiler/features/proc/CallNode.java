package org.evochora.compiler.features.proc;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.ISourceLocatable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AST node representing a CALL instruction. Carries REF/VAL/LREF/LVAL arguments
 * for procedure parameter passing.
 *
 * @param procedureName The name expression of the called procedure.
 * @param refArguments Scalar reference arguments from REF keyword.
 * @param valArguments Scalar value arguments from VAL keyword.
 * @param lrefArguments Location reference arguments from LREF keyword.
 * @param lvalArguments Location value arguments from LVAL keyword.
 * @param sourceInfo Source location of the CALL keyword.
 */
public record CallNode(
        AstNode procedureName,
        List<AstNode> refArguments,
        List<AstNode> valArguments,
        List<AstNode> lrefArguments,
        List<AstNode> lvalArguments,
        SourceInfo sourceInfo
) implements AstNode, ISourceLocatable {

    public CallNode {
        if (refArguments == null) refArguments = Collections.emptyList();
        if (valArguments == null) valArguments = Collections.emptyList();
        if (lrefArguments == null) lrefArguments = Collections.emptyList();
        if (lvalArguments == null) lvalArguments = Collections.emptyList();
    }

    @Override
    public List<AstNode> getChildren() {
        List<AstNode> children = new ArrayList<>();
        if (procedureName != null) children.add(procedureName);
        children.addAll(refArguments);
        children.addAll(valArguments);
        children.addAll(lrefArguments);
        children.addAll(lvalArguments);
        return children;
    }

    @Override
    public AstNode reconstructWithChildren(List<AstNode> newChildren) {
        int idx = 0;
        AstNode newProcName = procedureName != null ? newChildren.get(idx++) : null;
        List<AstNode> newRef = new ArrayList<>();
        for (int i = 0; i < refArguments.size(); i++) newRef.add(newChildren.get(idx++));
        List<AstNode> newVal = new ArrayList<>();
        for (int i = 0; i < valArguments.size(); i++) newVal.add(newChildren.get(idx++));
        List<AstNode> newLref = new ArrayList<>();
        for (int i = 0; i < lrefArguments.size(); i++) newLref.add(newChildren.get(idx++));
        List<AstNode> newLval = new ArrayList<>();
        for (int i = 0; i < lvalArguments.size(); i++) newLval.add(newChildren.get(idx++));
        return new CallNode(newProcName, newRef, newVal, newLref, newLval, sourceInfo);
    }
}
