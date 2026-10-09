package org.evochora.compiler.features.control;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IJumpTarget;
import org.evochora.compiler.model.ast.ISourceLocatable;

import java.util.List;

/**
 * An AST node that represents a case of a control block: the place set by {@code .CASE <Case>}
 * and the statements from there up to the next {@code .CASE} or the {@code .ENDCONTROL}.
 * The case's name is a label at its place, on the level of the block it belongs to.
 *
 * @param name       The name of the case.
 * @param exported   Whether {@code EXPORT} stands before the {@code .CASE}.
 * @param statements The statements of the case, in text order.
 * @param sourceInfo Source location of the case name.
 */
public record ControlCase(
        String name,
        boolean exported,
        List<AstNode> statements,
        SourceInfo sourceInfo
) implements AstNode, ISourceLocatable, IJumpTarget {

    @Override
    public List<AstNode> getChildren() {
        return statements;
    }

    @Override
    public AstNode reconstructWithChildren(List<AstNode> newChildren) {
        return new ControlCase(name, exported, newChildren, sourceInfo);
    }
}
