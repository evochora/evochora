package org.evochora.compiler.features.importdir;

import java.util.Locale;
import java.util.Optional;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.ISymbolCollector;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.ISourceLocatable;
import org.evochora.compiler.model.ast.OperandNode;
import org.evochora.compiler.model.symbols.ModuleScope;
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
 * carries a default, and defines its constant on the module level, as a {@code .CONST} is
 * defined: with a {@link ValueBindingNode} that binds the name to the value the import of the
 * placement bound, found in the placement's {@link Requirements}, or else to the configured
 * value or the default. The import that creates the placement stands above the placement's
 * tokens, so its bindings are recorded when the walk reaches the requirement. The value of a
 * computation is the business of pass 2, {@link RequireAnalysisHandler}.
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
            String name = valueNode.name().toUpperCase(Locale.ROOT);
            Optional<ModuleScope> module = symbolTable.getModuleScope(symbolTable.getCurrentAliasChain());
            module.ifPresent(scope -> scope.update(Requirements.class, Requirements::none,
                    r -> r.withValue(name, valueNode.defaultValue() != null)));
            OperandNode bound = module.flatMap(scope -> scope.record(Requirements.class, Requirements::none).boundValue(name))
                    .orElse(null);
            // The importer's binding first, then the configuration, then the default
            OperandNode value = bound != null ? bound
                    : valueNode.configured() != null ? valueNode.configured()
                    : valueNode.defaultValue() instanceof OperandNode defaultValue ? defaultValue : null;
            SourceInfo at = valueNode.sourceInfo();
            // Defined even without a value, so that a use of it reports nothing of its own
            symbolTable.define(new Symbol(valueNode.name(), at, Symbol.Type.CONSTANT, new ValueBindingNode(value, valueNode),
                            valueNode.exported()))
                    .ifPresent(existing -> diagnostics.reportError(
                            "Cannot require value '" + valueNode.name() + "': the name is already used at " + SourceInfo.position(existing.sourceInfo()) + ".",
                            at.fileName(), at.lineNumber()));
            return;
        }
        RequireNode requireNode = (RequireNode) node;
        symbolTable.define(new Symbol(requireNode.alias(), requireNode.sourceInfo(), Symbol.Type.MODULE_ALIAS, requireNode))
                .ifPresent(existing -> diagnostics.reportError(
                        "Cannot require as '" + requireNode.alias() + "': the name is already used at " + SourceInfo.position(existing.sourceInfo()) + ".",
                        requireNode.sourceInfo().fileName(), requireNode.sourceInfo().lineNumber()));
        symbolTable.getModuleScope(symbolTable.getCurrentAliasChain()).ifPresent(scope ->
                scope.update(Requirements.class, Requirements::none,
                        r -> r.withModule(requireNode.alias().toUpperCase(Locale.ROOT), requireNode.path())));
    }
}
