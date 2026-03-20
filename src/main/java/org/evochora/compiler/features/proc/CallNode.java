package org.evochora.compiler.features.proc;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.ISourceLocatable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AST node representing a CALL instruction. Separates procedure call semantics
 * from generic instructions, carrying REF/VAL arguments (new syntax) or
 * legacy WITH arguments.
 *
 * @param procedureName The name expression of the called procedure.
 * @param refArguments REF arguments (new syntax). Empty if legacy syntax.
 * @param valArguments VAL arguments (new syntax). Empty if legacy syntax.
 * @param legacyArguments Old-style WITH arguments. Empty if new syntax.
 * @param sourceInfo Source location of the CALL keyword.
 */
public record CallNode(
        AstNode procedureName,
        List<AstNode> refArguments,
        List<AstNode> valArguments,
        List<AstNode> legacyArguments,
        SourceInfo sourceInfo
) implements AstNode, ISourceLocatable {

    public CallNode {
        if (refArguments == null) refArguments = Collections.emptyList();
        if (valArguments == null) valArguments = Collections.emptyList();
        if (legacyArguments == null) legacyArguments = Collections.emptyList();
    }

    @Override
    public List<AstNode> getChildren() {
        List<AstNode> children = new ArrayList<>();
        if (procedureName != null) children.add(procedureName);
        children.addAll(refArguments);
        children.addAll(valArguments);
        children.addAll(legacyArguments);
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
        List<AstNode> newLegacy = new ArrayList<>();
        for (int i = 0; i < legacyArguments.size(); i++) newLegacy.add(newChildren.get(idx++));
        return new CallNode(newProcName, newRef, newVal, newLegacy, sourceInfo);
    }
}
