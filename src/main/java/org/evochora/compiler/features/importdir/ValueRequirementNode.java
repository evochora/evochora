package org.evochora.compiler.features.importdir;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.ISourceLocatable;

import java.util.List;

/**
 * AST node for a value requirement, {@code [EXPORT] .REQUIRE VALUE <Name> [<Default>]}.
 *
 * <p>The requirement defines the constant {@code <Name>} on the module's level, with the value
 * the importer binds, or the default. The bound value lives in the symbol the placement defines,
 * not in the node: the value slot holds an identifier with the requirement's own name, which
 * phase 6 resolves through that symbol and replaces by the literal at the end of its chain, so
 * that phase 7 can write the value of each placement into the artifact.
 *
 * @param name         The name of the value.
 * @param value        The value slot: the identifier of the name as the parser read it, the
 *                     literal after phase 6.
 * @param defaultValue The default, or {@code null} when the requirement has none.
 * @param exported     Whether the value is reachable from outside the module (the EXPORT prefix).
 * @param sourceInfo   The source location of the name.
 */
public record ValueRequirementNode(
        String name,
        AstNode value,
        AstNode defaultValue,
        boolean exported,
        SourceInfo sourceInfo
) implements AstNode, ISourceLocatable {

    @Override
    public List<AstNode> getChildren() {
        return defaultValue != null ? List.of(value, defaultValue) : List.of(value);
    }

    /**
     * Phase 6 puts the resolved value and default into the node through this.
     */
    @Override
    public AstNode reconstructWithChildren(List<AstNode> newChildren) {
        return new ValueRequirementNode(name, newChildren.get(0),
                defaultValue != null ? newChildren.get(1) : null, exported, sourceInfo);
    }
}
