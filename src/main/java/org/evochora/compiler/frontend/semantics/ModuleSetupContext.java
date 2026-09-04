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

    /**
     * Creates a context bound to one module of the dependency graph.
     *
     * @param symbolTable       The symbol table module scopes are registered in and read from.
     * @param diagnostics       Collects errors reported while wiring modules together.
     * @param pathToAliasChain  The live map from module source path to alias chain, shared by
     *                          all contexts of one setup run; handlers add entries for the
     *                          modules they pull in.
     * @param currentModulePath Source path of the module whose dependencies are processed
     *                          through this context.
     */
    public ModuleSetupContext(SymbolTable symbolTable, DiagnosticsEngine diagnostics,
                              Map<String, String> pathToAliasChain, String currentModulePath) {
        this.symbolTable = symbolTable;
        this.diagnostics = diagnostics;
        this.pathToAliasChain = pathToAliasChain;
        this.currentModulePath = currentModulePath;
    }

    /**
     * Provides access to module scopes. A module has a scope only after it has been
     * registered, which happens between the first and the second setup pass.
     *
     * @return The symbol table of the running compilation.
     */
    public SymbolTable symbolTable() { return symbolTable; }

    /**
     * Errors reported here are collected for the compilation as a whole; reporting one does
     * not stop the remaining setup passes.
     *
     * @return The diagnostics engine of the running compilation.
     */
    public DiagnosticsEngine diagnostics() { return diagnostics; }

    /**
     * Exposes the mapping from module source path to alias chain that is built up during
     * setup. The map is mutable and shared: a handler that assigns a chain to a module it
     * imports writes it here, and the later passes read it back.
     *
     * @return The live path-to-alias-chain map.
     */
    /**
     * Records the placement a module file got: pass 1 of the setup writes it, the later passes
     * read it through {@link #aliasChainOf}.
     *
     * @param resolvedPath The module file's resolved path.
     * @param aliasChain   The alias chain of its placement.
     */
    public void bindPath(String resolvedPath, String aliasChain) { pathToAliasChain.put(resolvedPath, aliasChain); }

    /**
     * Looks up the placement a module file got in pass 1.
     *
     * @param resolvedPath The module file's resolved path.
     * @return The alias chain of its placement, or {@code null} if pass 1 has not seen the file.
     */
    public String aliasChainOf(String resolvedPath) { return pathToAliasChain.get(resolvedPath); }

    /**
     * Identifies whose dependencies are currently being processed.
     *
     * @return The source path of that module; it is the key {@link #currentAliasChain()}
     *         looks up through {@link #aliasChainOf}.
     */
    public String currentModulePath() { return currentModulePath; }

    /**
     * Returns the alias chain for the current module.
     *
     * @return The chain registered for the current module path, or null if none has been
     *         assigned to it.
     */
    public String currentAliasChain() {
        return pathToAliasChain.get(currentModulePath);
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
