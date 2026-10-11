package org.evochora.compiler.features.importdir;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.ISymbolCollector;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.ISourceLocatable;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;

/**
 * Pass-1 symbol collector for {@code .REQUIRE} directives.
 *
 * <p>For a module requirement, registers the require alias as a symbol for conflict detection
 * (prevents labels, procedures, or constants from using the same name). The symbol is never
 * exported: a requirement is satisfied by the importer, so a path from outside never continues
 * through it. A requirement is a name of the module level: inside any level the symbol table has
 * opened, such as a procedure, the directive is reported and defines nothing.
 *
 * <p>The requirement itself (alias → path) is declared in the placement's {@link Requirements},
 * against which {@link ImportAnalysisHandler} checks the import of the placement. The module the
 * alias leads to is bound by the importer's {@link ImportSymbolCollector}.
 *
 * <p>A value requirement is declared in the placement's {@link Requirements}, with whether it
 * carries a default, and defines nothing here: its constant is defined in pass 2 by
 * {@link RequireAnalysisHandler}, as a {@code .CONST} is.
 */
public class RequireSymbolCollector implements ISymbolCollector {

    @Override
    public void collect(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        if (symbolTable.getCurrentScope() != symbolTable.getRootScope()) {
            SourceInfo at = ((ISourceLocatable) node).sourceInfo();
            diagnostics.reportError(".REQUIRE may stand only at the module level.", at.fileName(), at.lineNumber());
            return;
        }
        if (node instanceof ValueRequirementNode valueNode) {
            symbolTable.getModuleScope(symbolTable.getCurrentAliasChain()).ifPresent(scope ->
                    scope.update(Requirements.class, Requirements::none,
                            r -> r.withValue(valueNode.name().toUpperCase(), valueNode.defaultValue() != null)));
            return;
        }
        RequireNode requireNode = (RequireNode) node;
        symbolTable.define(new Symbol(requireNode.alias(), requireNode.sourceInfo(), Symbol.Type.MODULE_ALIAS, requireNode))
                .ifPresent(existing -> diagnostics.reportError(
                        "Cannot require as '" + requireNode.alias() + "': the name is already used at " + SourceInfo.position(existing.sourceInfo()) + ".",
                        requireNode.sourceInfo().fileName(), requireNode.sourceInfo().lineNumber()));
        symbolTable.getModuleScope(symbolTable.getCurrentAliasChain()).ifPresent(scope ->
                scope.update(Requirements.class, Requirements::none,
                        r -> r.withModule(requireNode.alias().toUpperCase(), requireNode.path())));
    }
}
