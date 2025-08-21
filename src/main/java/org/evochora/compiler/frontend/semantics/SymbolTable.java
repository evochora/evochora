package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Token;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Verwaltet Symbole (Labels, Konstanten etc.) für den Compiler.
 * Diese Implementierung unterstützt hierarchische Geltungsbereiche (Scopes)
 * über eine persistente Baumstruktur.
 */
public class SymbolTable {

    // NEU: Eine interne Klasse, die einen einzelnen Scope im Baum darstellt.
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
    private Scope currentScope; // Zeiger auf den aktuellen Scope im Baum
    private final DiagnosticsEngine diagnostics;
    private final Map<String, Map<String, String>> fileToAliasToTarget = new HashMap<>();

    public SymbolTable(DiagnosticsEngine diagnostics) {
        this.diagnostics = diagnostics;
        // Jede Symboltabelle beginnt mit einem globalen Wurzel-Scope.
        this.rootScope = new Scope(null);
        this.currentScope = this.rootScope;
    }

    /**
     * Setzt den internen Zeiger auf den Wurzel-Scope zurück.
     * Wichtig für Analysen, die mehrere Durchgänge über den Baum machen.
     */
    public void resetScope() {
        this.currentScope = this.rootScope;
    }


    /**
     * Betritt einen neuen, untergeordneten Geltungsbereich.
     * Statt auf einen Stack zu pushen, wird ein neuer Kind-Knoten im Baum erstellt.
     * @return Der neu erstellte Scope. // <-- NEUER JavaDoc-Kommentar
     */
    public Scope enterScope() {
        Scope newScope = new Scope(currentScope);
        currentScope.addChild(newScope);
        currentScope = newScope;
        return newScope;
    }

    /**
     * Setzt den internen Zeiger direkt auf einen bestimmten Scope.
     * @param scope Der Scope, der zum aktuellen gemacht werden soll.
     */
    public void setCurrentScope(Scope scope) {
        this.currentScope = scope;
    }

    /**
     * Verlässt den aktuellen Geltungsbereich.
     * Statt vom Stack zu poppen, wird zum Eltern-Knoten im Baum gewechselt.
     */
    public void leaveScope() {
        if (currentScope.parent != null) {
            currentScope = currentScope.parent;
        }
    }

    /**
     * Definiert ein neues Symbol im *aktuellen* Geltungsbereich.
     * Meldet einen Fehler, wenn das Symbol im aktuellen Scope bereits existiert.
     * @param symbol Das zu definierende Symbol.
     */
    public void define(Symbol symbol) {
        String name = symbol.name().text().toUpperCase();
        String file = symbol.name().fileName();
        // Die Logik bleibt gleich, arbeitet aber auf dem 'currentScope'.
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
     * Sucht nach einem Symbol, beginnend im aktuellen Scope und dann nach oben zum Wurzel-Scope.
     * @param name Das Token des gesuchten Symbols.
     * @return Ein Optional, das das Symbol enthält, falls es gefunden wurde.
     */
    public Optional<Symbol> resolve(Token name) {
        String key = name.text().toUpperCase();

        // Durchsuche die Scopes von innen nach außen, indem wir dem parent-Zeiger folgen.
        for (Scope scope = currentScope; scope != null; scope = scope.parent) {
            Map<String, Symbol> perFile = scope.symbols.get(key);
            if (perFile != null) {
                Symbol sym = perFile.get(name.fileName());
                if (sym != null) return Optional.of(sym);
            }
        }

        // Fallback für Namensräume (unverändert)
        int dot = key.indexOf('.');
        if (dot > 0) {
            String alias = key.substring(0, dot);
            String remainder = key.substring(dot + 1);
            String file = name.fileName();
            Map<String, String> aliasMap = fileToAliasToTarget.get(file);
            if (aliasMap != null) {
                String targetFile = aliasMap.get(alias);
                if (targetFile != null) {
                    for (Scope scope = currentScope; scope != null; scope = scope.parent) {
                        Map<String, Symbol> perFile = scope.symbols.get(remainder);
                        if (perFile != null) {
                            Symbol sym = null;
                            sym = perFile.get(targetFile);
                            if (sym == null) {
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
                                if (sym.type() == Symbol.Type.PROCEDURE) {
                                    Boolean exp = isProcedureExported(sym.name());
                                    if (Boolean.TRUE.equals(exp)) return Optional.of(sym);
                                } else {
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

    // Die restlichen Methoden bleiben unverändert.
    public void registerRequireAlias(String fileName, String aliasUpper, String targetFilePath) {
        if (fileName == null) return;
        if (targetFilePath == null) return;
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