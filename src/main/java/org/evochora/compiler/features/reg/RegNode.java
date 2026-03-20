package org.evochora.compiler.features.reg;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.ISourceLocatable;

import java.util.List;

/**
 * An AST node that represents a <code>.REG</code> directive.
 *
 * @param alias      The alias name.
 * @param register   The target register name (e.g., "%DR0").
 * @param sourceInfo The source location of the directive.
 */
public record RegNode(
        String alias,
        String register,
        SourceInfo sourceInfo
) implements AstNode, ISourceLocatable {

    @Override
    public List<AstNode> getChildren() {
        return List.of();
    }
}
