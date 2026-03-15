package org.evochora.compiler.features.importdir;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.analysis.ISymbolCollector;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.semantics.SymbolTable;

/**
 * Pass-1 symbol collector for {@code .IMPORT} directives.
 *
 * <p>Registers the import alias as a symbol in the current scope for conflict detection
 * (prevents labels, procedures, or constants from using the same name as an import alias).
 *
 * <p>The actual module relationship (alias → ModuleId) is registered by
 * {@code Compiler.setupModuleRelationships()} from the DependencyScanner's resolved data.
 */
public class ImportSymbolCollector implements ISymbolCollector {

    @Override
    public void collect(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        ImportNode importNode = (ImportNode) node;
        symbolTable.define(new Symbol(importNode.alias(), importNode.sourceInfo(), Symbol.Type.ALIAS, importNode));
    }
}
