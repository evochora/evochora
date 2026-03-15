package org.evochora.compiler.features.label;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.ISourceLocatable;

import java.util.List;

/**
 * An AST node that represents a label definition (e.g., "L1:" or "EXPORT L1:").
 *
 * @param name The name of the label.
 * @param sourceInfo The source location of the label definition.
 * @param statement The statement (typically an instruction) that follows this label.
 * @param exported Whether this label is exported for cross-file visibility.
 */
public record LabelNode(
        String name,
        SourceInfo sourceInfo,
        AstNode statement,
        boolean exported
) implements AstNode, ISourceLocatable {

    @Override
    public List<AstNode> getChildren() {
        return statement != null ? List.of(statement) : List.of();
    }

    @Override
    public AstNode reconstructWithChildren(List<AstNode> newChildren) {
        AstNode newStatement = newChildren.isEmpty() ? null : newChildren.get(0);
        return new LabelNode(name, sourceInfo, newStatement, exported);
    }
}
