package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.symbols.ModuleScope;
import org.evochora.compiler.model.symbols.SymbolTable;

import java.util.Map;

/**
 * Context provided to {@link IDependencySetupHandler} implementations during module setup.
 * Provides access to the symbol table, alias chain mappings, and the current module path.
 */
public class ModuleSetupContext {

    private final SymbolTable symbolTable;
    private final DiagnosticsEngine diagnostics;
    private final Map<String, String> pathToAliasChain;
    private final String currentModulePath;

    public ModuleSetupContext(SymbolTable symbolTable, DiagnosticsEngine diagnostics,
                              Map<String, String> pathToAliasChain, String currentModulePath) {
        this.symbolTable = symbolTable;
        this.diagnostics = diagnostics;
        this.pathToAliasChain = pathToAliasChain;
        this.currentModulePath = currentModulePath;
    }

    public SymbolTable symbolTable() { return symbolTable; }
    public DiagnosticsEngine diagnostics() { return diagnostics; }
    public Map<String, String> pathToAliasChain() { return pathToAliasChain; }
    public String currentModulePath() { return currentModulePath; }

    /**
     * Returns the alias chain for the current module.
     */
    public String currentAliasChain() {
        return pathToAliasChain.get(currentModulePath);
    }

    /**
     * Returns the module scope for the given alias chain, if registered.
     */
    public ModuleScope getModuleScope(String aliasChain) {
        return symbolTable.getModuleScope(aliasChain).orElse(null);
    }
}
