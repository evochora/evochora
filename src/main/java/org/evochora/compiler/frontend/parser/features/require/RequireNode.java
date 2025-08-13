package org.evochora.compiler.frontend.parser.features.require;

import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;

/**
 * Ein AST-Knoten, der eine .REQUIRE-Direktive repräsentiert.
 *
 * @param path Das String-Token des Dateipfads.
 * @param alias Das optionale Alias-Token (kann null sein).
 */
public record RequireNode(
        Token path,
        Token alias
) implements AstNode {
    // Dieser Knoten hat keine Kinder.
}