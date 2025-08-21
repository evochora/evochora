package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Token;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Stack; // NEU

/**
 * Verwaltet Symbole (Labels, Konstanten etc.) für den Compiler.
 * Diese Implementierung unterstützt hierarchische Geltungsbereiche (Scopes).
 */
public class SymbolTable {

    private final Stack<Map<String, java.util.Map<String, Symbol>>> scopes = new Stack<>(); // name -> (fileName -> symbol)
    private final DiagnosticsEngine diagnostics;
    // Per-file registry of aliases declared via .REQUIRE: file -> (alias -> targetFile)
    private final Map<String, java.util.Map<String, String>> fileToAliasToTarget = new HashMap<>();

    public SymbolTable(DiagnosticsEngine diagnostics) {
        this.diagnostics = diagnostics;
        // Jede Symboltabelle beginnt mit einem globalen Scope
        enterScope();
    }

    /**
     * Betritt einen neuen, untergeordneten Geltungsbereich.
     */
    public void enterScope() {
        scopes.push(new HashMap<>());
    }

    /**
     * Verlässt den aktuellen Geltungsbereich.
     */
    public void leaveScope() {
        if (scopes.size() > 1) { scopes.pop(); }
    }

    /**
     * Definiert ein neues Symbol im *aktuellen* Geltungsbereich.
     * Meldet einen Fehler, wenn das Symbol im aktuellen Scope bereits existiert.
     * @param symbol Das zu definierende Symbol.
     */
    public void define(Symbol symbol) {
        String name = symbol.name().text().toUpperCase();
        String file = symbol.name().fileName();
        Map<String, java.util.Map<String, Symbol>> currentScope = scopes.peek();
        java.util.Map<String, Symbol> perFile = currentScope.computeIfAbsent(name, k -> new java.util.HashMap<>());
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
     * Sucht nach einem Symbol, beginnend im aktuellen Scope und dann nach außen.
     * @param name Das Token des gesuchten Symbols.
     * @return Ein Optional, das das Symbol enthält, falls es gefunden wurde.
     */
    public Optional<Symbol> resolve(Token name) {
        String key = name.text().toUpperCase();
        // Durchsuche die Scopes von innen nach außen (vom Stack-Top nach unten)
        for (int i = scopes.size() - 1; i >= 0; i--) {
            Map<String, java.util.Map<String, Symbol>> scope = scopes.get(i);
            java.util.Map<String, Symbol> perFile = scope.get(key);
            if (perFile != null) {
                Symbol sym = perFile.get(name.fileName());
                if (sym != null) return Optional.of(sym);
            }
        }
        // Fallback: Unterstütze Namensräume per .REQUIRE Alias pro Datei.
        // Wenn das Symbol die Form ALIAS.NAME hat und ALIAS in derselben Datei per .REQUIRE registriert wurde,
        // versuche NAME aufzulösen – aber nur, wenn das Label aus der require-Zieldatei stammt.
        int dot = key.indexOf('.');
        if (dot > 0) {
            String alias = key.substring(0, dot);
            String remainder = key.substring(dot + 1); // kann weitere Punkte enthalten (LIB.PKG.NAME)
            String file = name.fileName();
            java.util.Map<String, String> aliasMap = fileToAliasToTarget.get(file);
            if (aliasMap != null) {
                String targetFile = aliasMap.get(alias);
                if (targetFile != null) {
                    for (int i = scopes.size() - 1; i >= 0; i--) {
                        Map<String, java.util.Map<String, Symbol>> scope = scopes.get(i);
                        java.util.Map<String, Symbol> perFile = scope.get(remainder);
                        if (perFile != null) {
                            Symbol sym = null;
                            // 1) Versuche exakte Übereinstimmung des Dateinamens
                            sym = perFile.get(targetFile);
                            if (sym == null) {
                                // 2) Suffix-Matching (normalisierte Separatoren), falls absolute/relative Mischungen vorliegen
                                String targetNorm = targetFile.replace('\\', '/');
                                for (Map.Entry<String, Symbol> e : perFile.entrySet()) {
                                    String fileNorm = e.getKey() != null ? e.getKey().replace('\\', '/') : null;
                                    if (fileNorm != null && (fileNorm.equals(targetNorm) || fileNorm.endsWith(targetNorm) || fileNorm.endsWith("/" + targetNorm))) {
                                        sym = e.getValue();
                                        break;
                                    }
                                }
                            }
                            if (sym != null) {
                                // Wenn es sich um eine Prozedur handelt, muss sie exportiert sein
                                if (sym.type() == Symbol.Type.PROCEDURE) {
                                    Boolean exp = isProcedureExported(sym.name());
                                    if (Boolean.TRUE.equals(exp)) return Optional.of(sym);
                                } else {
                                    // Andere Symboltypen dürfen nicht über .REQUIRE zugreifbar sein
                                    return Optional.empty();
                                }
                            }
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Registriert einen Alias aus einer .REQUIRE Direktive für die angegebene Datei.
     * Aliasse werden in Großschreibung gespeichert.
     * @param fileName Der logische Dateiname, in dem die .REQUIRE-Direktive steht.
     * @param aliasUpper Der Alias in Großschreibung.
     */
    public void registerRequireAlias(String fileName, String aliasUpper, String targetFilePath) {
        if (fileName == null) return;
        if (targetFilePath == null) return;
        fileToAliasToTarget.computeIfAbsent(fileName, k -> new java.util.HashMap<>()).put(aliasUpper, targetFilePath);
    }

    // --- Procedure Export Metadata ---
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