package org.evochora.compiler.features.importdir;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.IAnalysisHandler;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.OperandNode;
import org.evochora.compiler.model.symbols.Resolution;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;

/**
 * Pass-2 analysis handler for {@code .REQUIRE} directives.
 *
 * <p>Reports a module requirement of the main program: nothing imports the main program, so
 * nothing can supply the module, whether or not the alias is used. A requirement of a module is
 * supplied through a {@code USING} clause of the importer's {@code .IMPORT}, which
 * {@link ImportAnalysisHandler} checks.
 *
 * <p>Defines the constant of a value requirement, as {@code .CONST} defines its constant in this
 * pass: with the value the import of the placement bound, found in the placement's
 * {@link Requirements}, or else the default, through a {@link ValueBindingNode}. A default that is
 * a name is resolved here, at the line, as a use. Without binding and default the constant is
 * defined without a value, so that its uses report nothing of their own; in the main program the
 * missing value is reported here, in a module by the import.
 */
public class RequireAnalysisHandler implements IAnalysisHandler {

    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        if (node instanceof ValueRequirementNode valueNode) {
            analyzeValue(valueNode, symbolTable, diagnostics);
            return;
        }
        RequireNode requireNode = (RequireNode) node;
        if (isRoot(symbolTable)) {
            diagnostics.reportError(
                    "The main program cannot require a module: nothing imports it, so nothing can supply '"
                            + requireNode.alias() + "'; import \"" + requireNode.path() + "\" instead.",
                    requireNode.sourceInfo().fileName(),
                    requireNode.sourceInfo().lineNumber());
        }
    }

    private static void analyzeValue(ValueRequirementNode valueNode, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        // A requirement inside a level was reported in pass 1 and defines nothing
        if (symbolTable.getCurrentScope() != symbolTable.getRootScope()) {
            return;
        }
        SourceInfo at = valueNode.sourceInfo();
        OperandNode bound = symbolTable.getModuleScope(symbolTable.getCurrentAliasChain())
                .flatMap(scope -> scope.record(Requirements.class, Requirements::none).boundValue(valueNode.name().toUpperCase()))
                .orElse(null);
        // A default that is a name is a use of that name here, bound or not: it is defined before
        // this line, whatever the importer binds
        if (valueNode.defaultValue() instanceof IdentifierNode id
                && symbolTable.resolve(id.text(), id.sourceInfo()) instanceof Resolution.Missing missing) {
            diagnostics.reportError(
                    "Cannot require value '" + valueNode.name() + "' from '" + id.text() + "': " + missing.explanation(),
                    at.fileName(), at.lineNumber());
        }
        OperandNode value = bound;
        if (value == null && valueNode.defaultValue() instanceof OperandNode defaultValue) {
            value = defaultValue;
        }
        // Defined even without a value, so that a use of it reports nothing of its own
        symbolTable.define(new Symbol(valueNode.name(), at, Symbol.Type.CONSTANT, new ValueBindingNode(value), valueNode.exported()))
                .ifPresent(existing -> diagnostics.reportError(
                        "Cannot require value '" + valueNode.name() + "': the name is already used at " + SourceInfo.position(existing.sourceInfo()) + ".",
                        at.fileName(), at.lineNumber()));
        if (value == null && isRoot(symbolTable)) {
            diagnostics.reportError(
                    "The value '" + valueNode.name() + "' has no default and the configuration sets none; set it with values { "
                            + valueNode.name() + " = \"<value>\" } in the organism's entry or --value " + valueNode.name() + "=<value>.",
                    at.fileName(), at.lineNumber());
        }
    }

    private static boolean isRoot(SymbolTable symbolTable) {
        String root = symbolTable.rootAliasChain();
        return root != null && root.equals(symbolTable.getCurrentAliasChain());
    }
}
