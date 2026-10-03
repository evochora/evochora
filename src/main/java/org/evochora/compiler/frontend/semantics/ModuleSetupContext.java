package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.symbols.ModuleScope;
import org.evochora.compiler.model.symbols.SymbolTable;

/**
 * Context provided to {@link IDependencySetupHandler} implementations during module setup.
 * Provides access to the symbol table and names the module placement whose dependencies are
 * being processed.
 */
public class ModuleSetupContext {

    private final SymbolTable symbolTable;
    private final DiagnosticsEngine diagnostics;
    private final String currentAliasChain;

    /**
     * Creates a context bound to one module placement of the dependency graph.
     *
     * @param symbolTable       The symbol table module scopes are registered in and read from.
     * @param diagnostics       Collects errors reported while wiring modules together.
     * @param currentAliasChain Alias chain of the placement whose dependencies are processed
     *                          through this context.
     */
    public ModuleSetupContext(SymbolTable symbolTable, DiagnosticsEngine diagnostics, String currentAliasChain) {
        this.symbolTable = symbolTable;
        this.diagnostics = diagnostics;
        this.currentAliasChain = currentAliasChain;
    }

    /**
     * Provides access to module scopes. Every placement has a scope before the first setup
     * step runs.
     *
     * @return The symbol table of the running compilation.
     */
    public SymbolTable symbolTable() { return symbolTable; }

    /**
     * Errors reported here are collected for the compilation as a whole; reporting one does
     * not stop the remaining setup steps.
     *
     * @return The diagnostics engine of the running compilation.
     */
    public DiagnosticsEngine diagnostics() { return diagnostics; }

    /**
     * Returns the alias chain of the placement whose dependencies are being processed.
     *
     * @return The chain; empty for a main module compiled without a prefix.
     */
    public String currentAliasChain() {
        return currentAliasChain;
    }

    /**
     * Returns the module scope for the given alias chain, if registered.
     *
     * @param aliasChain The fully qualified chain identifying the module.
     * @return The module's scope, or null if no module is registered under that chain.
     */
    public ModuleScope getModuleScope(String aliasChain) {
        return symbolTable.getModuleScope(aliasChain).orElse(null);
    }
}
