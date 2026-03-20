package org.evochora.compiler.features.define;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.ISourceLocatable;

import java.util.List;

/**
 * An AST node that represents a <code>.define</code> directive (e.g., ".DEFINE X 42" or "EXPORT .DEFINE X 42").
 *
 * @param name       The constant name.
 * @param sourceInfo The source location of the constant name.
 * @param value      The AST node that represents the value of the constant.
 * @param exported   Whether this constant is exported for cross-module visibility.
 */
public record DefineNode(
        String name,
        SourceInfo sourceInfo,
        AstNode value,
        boolean exported
) implements AstNode, ISourceLocatable {

    /**
     * Constructs a non-exported define node.
     *
     * @param name       The constant name.
     * @param sourceInfo The source location of the constant name.
     * @param value      The AST node that represents the value of the constant.
     */
    public DefineNode(String name, SourceInfo sourceInfo, AstNode value) {
        this(name, sourceInfo, value, false);
    }

    @Override
    public List<AstNode> getChildren() {
        return List.of(value);
    }
}
