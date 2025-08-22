package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Token;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SymbolTable {

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

    public SymbolTable(DiagnosticsEngine diagnostics) {
        this.diagnostics = diagnostics;
        this.rootScope = new Scope(null);
        this.currentScope = this.rootScope;
    }

    public void resetScope() {
        this.currentScope = this.rootScope;
    }

    public Scope enterScope() {
        Scope newScope = new Scope(currentScope);
        currentScope.addChild(newScope);
        currentScope = newScope;
        return newScope;
    }

    public void leaveScope() {
        if (currentScope.parent != null) {
            currentScope = currentScope.parent;
        }
    }

    public void setCurrentScope(Scope scope) {
        this.currentScope = scope;
    }

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

    public void registerRequireAlias(String fileName, String aliasUpper, String targetFilePath) {
        if (fileName == null || targetFilePath == null) return;
        fileToAliasToTarget.computeIfAbsent(fileName, k -> new HashMap<>()).put(aliasUpper, targetFilePath);
    }

    private final Map<String, Boolean> procExportedByFileAndName = new HashMap<>();
    public void registerProcedureMeta(Token procName, boolean exported) {
        String key = procName.fileName() + "|" + procName.text().toUpperCase();
        procExportedByFileAndName.put(key, exported);
    }
    private Boolean isProcedureExported(Token procName) {
        String key = procName.fileName() + "|" + procName.text().toUpperCase();
        return procExportedByFileAndName.get(key);
    }
}