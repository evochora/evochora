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

    private final Stack<Map<String, Symbol>> scopes = new Stack<>(); // GEÄNDERT: von Map zu Stack<Map>
    private final DiagnosticsEngine diagnostics;

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
        if (scopes.size() > 1) { // Der globale Scope sollte nie verlassen werden
            scopes.pop();
        }
    }

    /**
     * Definiert ein neues Symbol im *aktuellen* Geltungsbereich.
     * Meldet einen Fehler, wenn das Symbol im aktuellen Scope bereits existiert.
     * @param symbol Das zu definierende Symbol.
     */
    public void define(Symbol symbol) {
        String name = symbol.name().text().toUpperCase();
        // Prüfe nur den obersten Scope auf Duplikate
        if (scopes.peek().containsKey(name)) {
            diagnostics.reportError(
                    "Symbol '" + name + "' is already defined in this scope.",
                    symbol.name().text(), // Dateiname ist hier nicht einfach verfügbar, Text ist besser als "Unknown"
                    symbol.name().line()
            );
        } else {
            scopes.peek().put(name, symbol);
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
            if (scopes.get(i).containsKey(key)) {
                return Optional.of(scopes.get(i).get(key));
            }
        }
        return Optional.empty();
    }
}