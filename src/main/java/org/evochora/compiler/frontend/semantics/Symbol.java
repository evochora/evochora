package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.frontend.lexer.Token;

/**
 * Repräsentiert ein einzelnes Symbol (z.B. ein Label, eine Konstante oder eine Prozedur)
 * in der Symboltabelle.
 *
 * @param name Das Token des Symbols, das Name, Zeile und Spalte enthält.
 * @param type Der Typ des Symbols.
 */
public record Symbol(Token name, Symbol.Type type) {
    public enum Type {
        LABEL,
        CONSTANT,
        PROCEDURE,
        VARIABLE // Für zukünftige Erweiterungen
    }
}