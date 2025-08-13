package org.evochora.compiler.core.ast;

/**
 * Das Basis-Interface für alle Knoten im Abstract Syntax Tree (AST).
 * Jeder Teil des Programms (eine Instruktion, eine Direktive, ein Ausdruck)
 * wird durch einen Knoten repräsentiert, der dieses Interface implementiert.
 */
public interface AstNode {
    /**
     * Akzeptiert einen Visitor. Teil des Visitor-Patterns.
     * @param visitor Der Visitor, der diesen Knoten besuchen soll.
     * @param <T> Der Rückgabetyp des Visitors.
     * @return Das Ergebnis der visit-Operation.
     */
    <T> T accept(AstVisitor<T> visitor);
}
