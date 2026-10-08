package org.evochora.compiler.features.label;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IJumpTarget;
import org.evochora.compiler.model.ast.ISourceLocatable;

import java.util.List;

/**
 * An AST node that represents a label definition (e.g., "L1:" or "EXPORT L1:").
 * The label names the position of the statement that follows it; that statement is a node
 * of its own, so a label has no children.
 *
 * @param name The name of the label.
 * @param sourceInfo The source location of the label definition.
 * @param exported Whether this label is exported for cross-file visibility.
 */
public record LabelNode(
        String name,
        SourceInfo sourceInfo,
        boolean exported
) implements AstNode, ISourceLocatable, IJumpTarget {

    @Override
    public List<AstNode> getChildren() {
        return List.of();
    }

    @Override
    public AstNode reconstructWithChildren(List<AstNode> newChildren) {
        return this;
    }
}
