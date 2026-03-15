package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.ast.AstNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A module-aware symbol table for managing scopes and symbols during semantic analysis.
 * Supports nested procedure scopes within each module, qualified cross-module name resolution
 * via import aliases, and export-based visibility control.
 *
 * <p>Modules are identified by their import alias chain (e.g., "PRED.MATH") rather than
 * by file path, allowing the same physical file to appear as distinct placements with
 * independent symbol namespaces.</p>
 *
 * <p>For single-file compilations, the table operates with a default module and behaves
 * identically to the pre-module-system version.</p>
 */
public class SymbolTable {

    /**
     * Represents a single scope in the symbol table (procedure-local or module-global).
     * Each scope has a human-readable name used for display and annotations (e.g., "global",
     * "MAIN.INIT"). Scope identity is determined by object reference, not by name.
     */
    public static class Scope {
        private final Scope parent;
        private final String name;
        private final List<Scope> children = new ArrayList<>();
        private final Map<String, Map<String, Symbol>> symbols = new HashMap<>(); // name -> (fileName -> symbol)

        Scope(Scope parent, String name) {
            this.parent = parent;
            this.name = name;
        }

        public String name() {
            return name;
        }

        void addChild(Scope child) {
            children.add(child);
        }
    }

    // --- Module-aware primary structure (keyed by alias chain) ---
    private final Map<String, ModuleScope> modules = new HashMap<>();
    private String currentAliasChain;

    // --- Procedure-local scope hierarchy (within the current module) ---
    private final Scope rootScope;
    private Scope currentScope;

    // --- Node-to-scope mapping (populated by ProcedureSymbolCollector, consumed by TokenMapGenerator) ---
    private final Map<AstNode, Scope> nodeScopeMap = new HashMap<>();

    private final DiagnosticsEngine diagnostics;

    /**
     * Constructs a new symbol table. The current module must be set via
     * {@link #setCurrentModule(String)} before any define/resolve operations.
     * @param diagnostics The diagnostics engine for reporting errors.
     */
    public SymbolTable(DiagnosticsEngine diagnostics) {
        this.diagnostics = diagnostics;
        this.rootScope = new Scope(null, "global");
        this.currentScope = this.rootScope;
    }

    // === Module management ===

    /**
     * Registers a module in the symbol table.
     * @param aliasChain The alias chain identifying this module placement (e.g., "PRED.MATH").
     * @param sourcePath The file path or URL of the module source.
     */
    public void registerModule(String aliasChain, String sourcePath) {
        modules.computeIfAbsent(aliasChain, ac -> new ModuleScope(ac, sourcePath));
    }

    /**
     * Sets the current module context. All subsequent define/resolve operations
     * operate within this module.
     * @param aliasChain The alias chain of the module to set as current.
     */
    public void setCurrentModule(String aliasChain) {
        this.currentAliasChain = aliasChain;
        if (!modules.containsKey(aliasChain)) {
            modules.put(aliasChain, new ModuleScope(aliasChain, aliasChain));
        }
    }

    /**
     * Gets the current module alias chain.
     */
    public String getCurrentAliasChain() {
        return currentAliasChain;
    }

    /**
     * Gets the module scope for the given alias chain, or empty if not registered.
     */
    public Optional<ModuleScope> getModuleScope(String aliasChain) {
        return Optional.ofNullable(modules.get(aliasChain));
    }

    /**
     * Gets the module scope map (for multi-module iteration).
     */
    public Map<String, ModuleScope> getModules() {
        return modules;
    }

    // === Scope management ===

    /**
     * Resets the current scope to the root scope.
     */
    public void resetScope() {
        this.currentScope = this.rootScope;
    }

    /**
     * Enters a new named scope.
     * @param name A human-readable scope name for display and annotations (e.g., "MAIN.INIT").
     * @return The new scope.
     */
    public Scope enterScope(String name) {
        Scope newScope = new Scope(currentScope, name);
        currentScope.addChild(newScope);
        currentScope = newScope;
        return newScope;
    }

    /**
     * Leaves the current scope and moves to the parent scope.
     */
    public void leaveScope() {
        if (currentScope.parent != null) {
            currentScope = currentScope.parent;
        }
    }

    /**
     * Sets the current scope to the given scope.
     * @param scope The scope to set as current.
     */
    public void setCurrentScope(Scope scope) {
        this.currentScope = scope;
    }

    /**
     * Gets the current scope.
     * @return The current scope.
     */
    public Scope getCurrentScope() {
        return this.currentScope;
    }

    /**
     * Gets the root scope.
     * @return The root scope.
     */
    public Scope getRootScope() {
        return this.rootScope;
    }

    /**
     * Associates an AST node with its scope. Called by ProcedureSymbolCollector during pass 1.
     */
    public void registerNodeScope(AstNode node, Scope scope) {
        nodeScopeMap.put(node, scope);
    }

    /**
     * Returns the scope associated with the given AST node, or null if none.
     */
    public Scope getNodeScope(AstNode node) {
        return nodeScopeMap.get(node);
    }

    // === Symbol definition and resolution ===

    /**
     * Defines a new symbol in the current scope and registers it in the current module scope.
     * Reports an error if the symbol is already defined in the same file within the current scope.
     * @param symbol The symbol to define.
     */
    public void define(Symbol symbol) {
        String name = symbol.name().toUpperCase();
        String file = symbol.sourceInfo().fileName();

        // In module context, use the module's source path as file key.
        // This makes .SOURCE-included symbols resolvable from the module's own tokens.
        ModuleScope modScope = modules.get(currentAliasChain);
        if (modScope != null) {
            file = modScope.sourcePath();
        }

        // Register in the scope hierarchy (for procedure-local visibility)
        Map<String, Symbol> perFile = currentScope.symbols.computeIfAbsent(name, k -> new HashMap<>());
        if (perFile.containsKey(file)) {
            diagnostics.reportError(
                    "Symbol '" + name + "' is already defined in this scope.",
                    symbol.sourceInfo().fileName(),
                    symbol.sourceInfo().lineNumber()
            );
        } else {
            perFile.put(file, symbol);
        }

        // Register in the module scope (for cross-module visibility)
        if (modScope != null && currentScope == rootScope) {
            modScope.symbols().putIfAbsent(name, symbol);
        }
    }

    /**
     * Resolves a symbol by name, searching from the current scope upwards to the root.
     * If the symbol is not found, it attempts to resolve it as a qualified name
     * (e.g., {@code ALIAS.SYMBOL}) using the current module's import aliases.
     * @param name The name of the symbol to resolve.
     * @param requestingFile The file requesting the symbol resolution (for module scoping).
     * @return An optional containing the resolved symbol with its qualified name, or empty if not found.
     */
    public Optional<ResolvedSymbol> resolve(String name, String requestingFile) {
        String key = name.toUpperCase();

        // Search scope hierarchy (current scope → root)
        for (Scope scope = currentScope; scope != null; scope = scope.parent) {
            Map<String, Symbol> perFile = scope.symbols.get(key);
            if (perFile != null) {
                if (perFile.containsKey(requestingFile)) {
                    Symbol sym = perFile.get(requestingFile);
                    String qualified = qualifyName(key);
                    return Optional.of(new ResolvedSymbol(sym, qualified));
                }
            }
        }

        // Attempt qualified name resolution (ALIAS.SYMBOL or multi-level ALIAS.B.SYMBOL)
        int dot = key.indexOf('.');
        if (dot > 0) {
            String alias = key.substring(0, dot);
            String remainder = key.substring(dot + 1);

            ModuleScope resolveModScope = modules.get(currentAliasChain);
            if (resolveModScope != null) {
                String targetAliasChain = resolveModScope.imports().get(alias);
                if (targetAliasChain == null) {
                    targetAliasChain = resolveModScope.usingBindings().get(alias);
                }
                if (targetAliasChain != null) {
                    // Check for multi-level resolution (A.B.SYMBOL)
                    int nextDot = remainder.indexOf('.');
                    if (nextDot > 0) {
                        return resolveMultiLevel(targetAliasChain, remainder);
                    }

                    ModuleScope targetModScope = modules.get(targetAliasChain);
                    if (targetModScope != null) {
                        Symbol sym = targetModScope.symbols().get(remainder);
                        if (sym != null && isExported(sym)) {
                            String qualified = targetAliasChain.isEmpty()
                                    ? remainder : targetAliasChain + "." + remainder;
                            return Optional.of(new ResolvedSymbol(sym, qualified));
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Recursively resolves multi-level qualified names (e.g., "A.B.C.LABEL").
     * At each level, checks that the import is EXPORT-visible before descending.
     */
    private Optional<ResolvedSymbol> resolveMultiLevel(String currentChain, String remainder) {
        int dot = remainder.indexOf('.');
        if (dot <= 0) {
            ModuleScope modScope = modules.get(currentChain);
            if (modScope != null) {
                Symbol sym = modScope.symbols().get(remainder);
                if (sym != null && isExported(sym)) {
                    String qualified = currentChain.isEmpty()
                            ? remainder : currentChain + "." + remainder;
                    return Optional.of(new ResolvedSymbol(sym, qualified));
                }
            }
            return Optional.empty();
        }

        String nextAlias = remainder.substring(0, dot);
        String nextRemainder = remainder.substring(dot + 1);

        ModuleScope modScope = modules.get(currentChain);
        if (modScope == null) return Optional.empty();

        String nextChain = modScope.imports().get(nextAlias);
        if (nextChain == null) {
            nextChain = modScope.usingBindings().get(nextAlias);
        }
        if (nextChain == null) return Optional.empty();

        Boolean exp = modScope.importExported().get(nextAlias);
        if (!Boolean.TRUE.equals(exp)) return Optional.empty();

        return resolveMultiLevel(nextChain, nextRemainder);
    }

    /**
     * Qualifies a name with the current alias chain.
     */
    private String qualifyName(String nameUpper) {
        if (currentAliasChain != null && !currentAliasChain.isEmpty()) {
            return currentAliasChain + "." + nameUpper;
        }
        return nameUpper;
    }

    /**
     * Checks if a symbol is exported (visible to other modules).
     */
    private boolean isExported(Symbol sym) {
        return sym.exported();
    }

    /**
     * Returns all symbols from all scopes in the symbol table.
     * Used for generating debug information like TokenMap.
     *
     * @return A list of all symbols in the symbol table
     */
    public List<Symbol> getAllSymbols() {
        List<Symbol> allSymbols = new ArrayList<>();
        collectSymbols(rootScope, allSymbols);
        return allSymbols;
    }

    private void collectSymbols(Scope scope, List<Symbol> symbols) {
        for (Map<String, Symbol> perFile : scope.symbols.values()) {
            symbols.addAll(perFile.values());
        }
        for (Scope child : scope.children) {
            collectSymbols(child, symbols);
        }
    }
}
