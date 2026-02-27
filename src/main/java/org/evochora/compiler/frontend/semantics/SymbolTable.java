package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Token;

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
 * <p>For single-file compilations, the table operates with a default module and behaves
 * identically to the pre-module-system version.</p>
 */
public class SymbolTable {

    /**
     * Represents a single scope in the symbol table (procedure-local or module-global).
     */
    public static class Scope {
        private final Scope parent;
        private final List<Scope> children = new ArrayList<>();
        private final Map<String, Map<String, Symbol>> symbols = new HashMap<>(); // name -> (fileName -> symbol)

        Scope(Scope parent) {
            this.parent = parent;
        }

        void addChild(Scope child) {
            children.add(child);
        }
    }

    // --- Module-aware primary structure ---
    private final Map<ModuleId, ModuleScope> modules = new HashMap<>();
    private ModuleId currentModuleId;

    // --- Procedure-local scope hierarchy (within the current module) ---
    private final Scope rootScope;
    private Scope currentScope;

    private final DiagnosticsEngine diagnostics;

    // --- Export metadata ---
    private final Map<String, Boolean> procExportedByFileAndName = new HashMap<>();
    private final Map<String, Boolean> labelExportedByFileAndName = new HashMap<>();

    /**
     * Constructs a new symbol table. The current module must be set via
     * {@link #setCurrentModule(ModuleId)} before any define/resolve operations.
     * @param diagnostics The diagnostics engine for reporting errors.
     */
    public SymbolTable(DiagnosticsEngine diagnostics) {
        this.diagnostics = diagnostics;
        this.rootScope = new Scope(null);
        this.currentScope = this.rootScope;
    }

    // === Module management ===

    /**
     * Registers a module in the symbol table.
     * @param moduleId The module identity.
     * @param sourcePath The file path or URL of the module source.
     */
    public void registerModule(ModuleId moduleId, String sourcePath) {
        modules.computeIfAbsent(moduleId, id -> new ModuleScope(id, sourcePath));
    }

    /**
     * Sets the current module context. All subsequent define/resolve operations
     * operate within this module.
     * @param moduleId The module to set as current.
     */
    public void setCurrentModule(ModuleId moduleId) {
        this.currentModuleId = moduleId;
        if (!modules.containsKey(moduleId)) {
            modules.put(moduleId, new ModuleScope(moduleId, moduleId.path()));
        }
    }

    /**
     * Gets the current module ID.
     */
    public ModuleId getCurrentModuleId() {
        return currentModuleId;
    }

    /**
     * Gets the module scope for the given module ID, or empty if not registered.
     */
    public Optional<ModuleScope> getModuleScope(ModuleId moduleId) {
        return Optional.ofNullable(modules.get(moduleId));
    }

    /**
     * Gets the module scope map (for multi-module iteration).
     */
    public Map<ModuleId, ModuleScope> getModules() {
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
     * Enters a new scope.
     * @return The new scope.
     */
    public Scope enterScope() {
        Scope newScope = new Scope(currentScope);
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

    // === Symbol definition and resolution ===

    /**
     * Defines a new symbol in the current scope and registers it in the current module scope.
     * Reports an error if the symbol is already defined in the same file within the current scope.
     * @param symbol The symbol to define.
     */
    public void define(Symbol symbol) {
        String name = symbol.name().text().toUpperCase();
        String file = symbol.name().fileName();

        // Register in the scope hierarchy (for procedure-local visibility)
        Map<String, Symbol> perFile = currentScope.symbols.computeIfAbsent(name, k -> new HashMap<>());
        if (perFile.containsKey(file)) {
            diagnostics.reportError(
                    "Symbol '" + name + "' is already defined in this scope.",
                    symbol.name().fileName(),
                    symbol.name().line()
            );
        } else {
            perFile.put(file, symbol);
        }

        // Register in the module scope (for cross-module visibility)
        ModuleScope modScope = modules.get(currentModuleId);
        if (modScope != null && currentScope == rootScope) {
            modScope.symbols().putIfAbsent(name, symbol);
        }
    }

    /**
     * Resolves a symbol by name, searching from the current scope upwards to the root.
     * If the symbol is not found, it attempts to resolve it as a qualified name
     * (e.g., {@code ALIAS.SYMBOL}) using the current module's import aliases.
     * @param name The token of the symbol to resolve.
     * @return An optional containing the found symbol, or empty if not found.
     */
    public Optional<Symbol> resolve(Token name) {
        String key = name.text().toUpperCase();
        String requestingFile = name.fileName();

        // Search scope hierarchy (current scope → root)
        for (Scope scope = currentScope; scope != null; scope = scope.parent) {
            Map<String, Symbol> perFile = scope.symbols.get(key);
            if (perFile != null) {
                if (perFile.containsKey(requestingFile)) return Optional.of(perFile.get(requestingFile));
            }
        }

        // Attempt qualified name resolution (ALIAS.SYMBOL)
        int dot = key.indexOf('.');
        if (dot > 0) {
            String alias = key.substring(0, dot);
            String remainder = key.substring(dot + 1);

            ModuleScope resolveModScope = modules.get(currentModuleId);
            if (resolveModScope != null) {
                ModuleId targetModuleId = resolveModScope.imports().get(alias);
                if (targetModuleId == null) {
                    targetModuleId = resolveModScope.usingBindings().get(alias);
                }
                if (targetModuleId != null) {
                    ModuleScope targetModScope = modules.get(targetModuleId);
                    if (targetModScope != null) {
                        Symbol sym = targetModScope.symbols().get(remainder);
                        if (sym != null && isExported(sym)) {
                            return Optional.of(sym);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Checks if a symbol is exported (visible to other modules).
     */
    private boolean isExported(Symbol sym) {
        if (sym.exported()) return true;
        // Fall back to metadata-based export check
        if (sym.type() == Symbol.Type.PROCEDURE) {
            Boolean exported = isProcedureExported(sym.name());
            return Boolean.TRUE.equals(exported);
        }
        if (sym.type() == Symbol.Type.LABEL) {
            Boolean exported = isLabelExported(sym.name());
            return Boolean.TRUE.equals(exported);
        }
        return false;
    }

    // === Export metadata ===

    /**
     * Registers metadata for a procedure, such as whether it is exported.
     * @param procName The name token of the procedure.
     * @param exported True if the procedure is exported, false otherwise.
     */
    public void registerProcedureMeta(Token procName, boolean exported) {
        String key = procName.fileName() + "|" + procName.text().toUpperCase();
        procExportedByFileAndName.put(key, exported);
    }

    private Boolean isProcedureExported(Token procName) {
        String key = procName.fileName() + "|" + procName.text().toUpperCase();
        return procExportedByFileAndName.get(key);
    }

    /**
     * Registers metadata for a label, such as whether it is exported.
     * @param labelName The name token of the label.
     * @param exported True if the label is exported, false otherwise.
     */
    public void registerLabelMeta(Token labelName, boolean exported) {
        String key = labelName.fileName() + "|" + labelName.text().toUpperCase();
        labelExportedByFileAndName.put(key, exported);
    }

    private Boolean isLabelExported(Token labelName) {
        String key = labelName.fileName() + "|" + labelName.text().toUpperCase();
        return labelExportedByFileAndName.get(key);
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
