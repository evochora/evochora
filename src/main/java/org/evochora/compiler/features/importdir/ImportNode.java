package org.evochora.compiler.features.importdir;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IPlacementAlias;
import org.evochora.compiler.model.ast.ISourceLocatable;
import org.evochora.compiler.model.ast.OperandNode;

import java.util.ArrayList;
import java.util.List;

/**
 * AST node for the {@code .IMPORT} directive.
 *
 * <p>Syntax: {@code [EXPORT] .IMPORT "path" AS ALIAS [USING {MODULE source AS target ... | VALUE name value ...} ...]}
 *
 * <p>The children are the bound values, so that the phases that walk the tree see a name or a
 * computation the importer writes as a value; phase 6 rebuilds the node with the values it put in
 * their place, so that phase 7 finds each computation only the layout computes.
 *
 * @param path       The imported file path (unquoted string value from the token).
 * @param alias      The local alias name for the imported module.
 * @param usings     The USING clauses providing dependencies to the imported module.
 * @param values     The values the import binds to value requirements of the imported module.
 * @param exported   Whether the import is passed on to the module's importers (the EXPORT
 *                   prefix), so that a path from outside may continue through the alias.
 * @param sourceInfo The source location of the alias token.
 */
public record ImportNode(
        String path,
        String alias,
        List<UsingClause> usings,
        List<ValueBinding> values,
        boolean exported,
        SourceInfo sourceInfo
) implements AstNode, ISourceLocatable, IPlacementAlias {

    @Override
    public List<AstNode> getChildren() {
        return values.stream().<AstNode>map(ValueBinding::value).toList();
    }

    /**
     * Phase 6 puts the resolved bound values into the node through this, one per binding, in the
     * order of the bindings.
     */
    @Override
    public AstNode reconstructWithChildren(List<AstNode> newChildren) {
        List<ValueBinding> rebuilt = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            ValueBinding binding = values.get(i);
            rebuilt.add(new ValueBinding(binding.name(), (OperandNode) newChildren.get(i), binding.nameSourceInfo()));
        }
        return new ImportNode(path, alias, usings, rebuilt, exported, sourceInfo);
    }

    /**
     * A USING clause on an import, providing a module to satisfy a dependency.
     *
     * @param sourceAlias      The alias being provided (must be a known import in the current module).
     * @param targetAlias      The alias being satisfied (must match a .REQUIRE in the imported module).
     * @param sourceSourceInfo The source location of the source alias token.
     * @param targetSourceInfo The source location of the target alias token.
     */
    public record UsingClause(
            String sourceAlias,
            String targetAlias,
            SourceInfo sourceSourceInfo,
            SourceInfo targetSourceInfo
    ) {}

    /**
     * A {@code VALUE name value} group on an import, binding a value to a value requirement.
     *
     * @param name           The name of the value requirement (must match a .REQUIRE VALUE in the
     *                       imported module).
     * @param value          The value, as written in the importer; a name is a constant of the
     *                       importer.
     * @param nameSourceInfo The source location of the name token.
     */
    public record ValueBinding(
            String name,
            OperandNode value,
            SourceInfo nameSourceInfo
    ) {}
}
