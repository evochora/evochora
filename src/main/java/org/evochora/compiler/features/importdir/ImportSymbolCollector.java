package org.evochora.compiler.features.importdir;

import org.evochora.compiler.api.QualifiedNames;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.ISymbolCollector;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.ModuleScope;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;

import java.util.Optional;

/**
 * Pass-1 symbol collector for {@code .IMPORT} directives.
 *
 * <p>Registers the import alias as a symbol in the current scope. The symbol keeps labels,
 * procedures, or constants from using the same name as an import alias, and it is the segment a
 * path leads through into the imported module: it is exported when the import is passed on with
 * {@code EXPORT .IMPORT}, so that a path from outside may continue through it. An import is a
 * name of the module level: inside any level the symbol table has opened, such as a procedure,
 * the directive is reported and defines nothing.
 *
 * <p>The import alias itself (alias → alias chain of the imported placement) is bound by
 * {@link ImportModuleSetupHandler} from the dependency scan's data. This collector binds the
 * {@code USING source AS target} clauses: in the imported placement, the target alias leads to
 * the placement the source alias leads to in the importing one. A source that leads nowhere binds
 * nothing; {@link ImportAnalysisHandler} reports it in pass 2.
 *
 * <p>Text order suffices for the bindings: the module's tokens follow the {@code .IMPORT} line, so
 * the importer's node is collected before anything of the module, and an alias the importer
 * received itself was bound by its own importer before any of the importer's tokens.
 */
public class ImportSymbolCollector implements ISymbolCollector {

    @Override
    public void collect(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        ImportNode importNode = (ImportNode) node;
        if (symbolTable.getCurrentScope() != symbolTable.getRootScope()) {
            diagnostics.reportError(".IMPORT may stand only at the module level.",
                    importNode.sourceInfo().fileName(), importNode.sourceInfo().lineNumber());
            return;
        }
        symbolTable.define(new Symbol(importNode.alias(), importNode.sourceInfo(), Symbol.Type.MODULE_ALIAS, importNode,
                        importNode.exported()))
                .ifPresent(existing -> diagnostics.reportError(
                        "Cannot import as '" + importNode.alias() + "': the name is already used at " + SourceInfo.position(existing.sourceInfo()) + ".",
                        importNode.sourceInfo().fileName(), importNode.sourceInfo().lineNumber()));

        String currentChain = symbolTable.getCurrentAliasChain();
        Optional<ModuleScope> currentScope = symbolTable.getModuleScope(currentChain);
        Optional<ModuleScope> importedScope = symbolTable.getModuleScope(QualifiedNames.join(currentChain, importNode.alias()));
        if (currentScope.isEmpty() || importedScope.isEmpty()) {
            return;
        }
        for (ImportNode.UsingClause using : importNode.usings()) {
            String sourceChain = currentScope.get().aliases().get(using.sourceAlias().toUpperCase());
            if (sourceChain != null) {
                importedScope.get().bindAlias(using.targetAlias().toUpperCase(), sourceChain);
            }
        }
    }
}
