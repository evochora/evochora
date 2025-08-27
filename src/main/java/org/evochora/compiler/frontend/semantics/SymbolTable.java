package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Token;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A symbol table for managing scopes and symbols during semantic analysis.
 * It supports nested scopes and resolving symbols based on the current scope.
 * It also handles cross-file symbol resolution via require/import aliases.
 */
public class SymbolTable {

    /**
     * Represents a single scope in the symbol table.
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

    private final Scope rootScope;
    private Scope currentScope;
    private final DiagnosticsEngine diagnostics;
    private final Map<String, Map<String, String>> fileToAliasToTarget = new HashMap<>();

    /**
     * Constructs a new symbol table.
     * @param diagnostics The diagnostics engine for reporting errors.
     */
    public SymbolTable(DiagnosticsEngine diagnostics) {
        this.diagnostics = diagnostics;
        this.rootScope = new Scope(null);
        this.currentScope = this.rootScope;
    }

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
     * Defines a new symbol in the current scope.
     * Reports an error if the symbol is already defined in the same file within the current scope.
     * @param symbol The symbol to define.
     */
    public void define(Symbol symbol) {
        String name = symbol.name().text().toUpperCase();
        String file = symbol.name().fileName();
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
    }

    /**
     * Resolves a symbol by name, searching from the current scope upwards to the root.
     * If the symbol is not found, it attempts to resolve it as a qualified name
     * (e.g., `alias.SYMBOL`) using registered import aliases.
     * @param name The token of the symbol to resolve.
     * @return An optional containing the found symbol, or empty if not found.
     */
    public Optional<Symbol> resolve(Token name) {
        String key = name.text().toUpperCase();
        String requestingFile = name.fileName();

        for (Scope scope = currentScope; scope != null; scope = scope.parent) {
            Map<String, Symbol> perFile = scope.symbols.get(key);
            if (perFile != null) {
                if (perFile.containsKey(requestingFile)) return Optional.of(perFile.get(requestingFile));
            }
        }

        int dot = key.indexOf('.');
        if (dot > 0) {
            String alias = key.substring(0, dot);
            String remainder = key.substring(dot + 1);

            Map<String, String> aliasMap = fileToAliasToTarget.get(requestingFile);
            if (aliasMap != null) {
                String targetFile = aliasMap.get(alias);
                if (targetFile != null) {
                    Optional<Symbol> symOpt = findSymbolInTree(rootScope, remainder, targetFile);
                    if (symOpt.isPresent()) {
                        Symbol sym = symOpt.get();
                        if (sym.type() == Symbol.Type.PROCEDURE && Boolean.TRUE.equals(isProcedureExported(sym.name()))) {
                            return Optional.of(sym);
                        }
                        return Optional.empty();
                    }
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Symbol> findSymbolInTree(Scope startScope, String symbolName, String targetFileName) {
        Map<String, Symbol> perFile = startScope.symbols.get(symbolName.toUpperCase());
        if (perFile != null) {
            String targetNorm = targetFileName.replace('\\', '/');
            for (Map.Entry<String, Symbol> entry : perFile.entrySet()) {
                String fileNorm = entry.getKey() != null ? entry.getKey().replace('\\', '/') : "";
                if (fileNorm.equals(targetNorm) || fileNorm.endsWith("/" + targetNorm)) {
                    return Optional.of(entry.getValue());
                }
            }
        }
        for (Scope child : startScope.children) {
            Optional<Symbol> found = findSymbolInTree(child, symbolName, targetFileName);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    /**
     * Registers a require alias for a specific file.
     * @param fileName The file in which the alias is defined.
     * @param aliasUpper The upper-case alias name.
     * @param targetFilePath The file path the alias points to.
     */
    public void registerRequireAlias(String fileName, String aliasUpper, String targetFilePath) {
        if (fileName == null || targetFilePath == null) return;
        fileToAliasToTarget.computeIfAbsent(fileName, k -> new HashMap<>()).put(aliasUpper, targetFilePath);
    }

    private final Map<String, Boolean> procExportedByFileAndName = new HashMap<>();

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
}