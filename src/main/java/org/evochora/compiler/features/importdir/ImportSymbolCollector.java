package org.evochora.compiler.features.importdir;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.ISymbolCollector;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;

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
 * <p>The module relationship itself (alias → alias chain of the imported placement) is registered
 * by {@link ImportModuleSetupHandler} from the dependency scan's data.
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
    }
}
