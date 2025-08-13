package org.evochora.compiler.frontend.parser.ast;

import java.util.Collections;
import java.util.List;

/**
 * Das Basis-Interface für alle Knoten im Abstract Syntax Tree (AST).
 */
public interface AstNode {
    /**
     * Gibt eine Liste der direkten Kind-Knoten zurück.
     * Dies ermöglicht einem generischen TreeWalker, den Baum zu durchlaufen,
     * ohne die spezifische Struktur jedes Knotens zu kennen.
     *
     * @return Eine Liste von Kind-Knoten. Gibt eine leere Liste zurück, wenn der Knoten keine Kinder hat.
     */
    default List<AstNode> getChildren() {
        return Collections.emptyList();
    }
}