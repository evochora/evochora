package org.evochora.compiler.core.directives;

import org.evochora.compiler.core.CompilerPhase;
import org.evochora.compiler.core.Parser;
import org.evochora.compiler.core.Token;
import org.evochora.compiler.core.TokenType;
import org.evochora.compiler.core.ast.AstNode;
import org.evochora.compiler.core.ast.NumberLiteralNode;
import org.evochora.compiler.core.ast.TypedLiteralNode;
import org.evochora.compiler.core.ast.VectorLiteralNode;

/**
 * Handler für die .DEFINE-Direktive.
 * Parst eine Konstantendefinition und fügt sie zur Symboltabelle des Parsers hinzu.
 */
public class DefineDirectiveHandler implements IDirectiveHandler {

    @Override
    public CompilerPhase getPhase() {
        return CompilerPhase.PARSING;
    }

    /**
     * Parst eine .DEFINE-Anweisung.
     * Erwartetes Format: .DEFINE <NAME> <LITERAL>
     * @param parser Der Parser, der den Handler aufruft.
     * @return {@code null}, da diese Direktive keinen AST-Knoten erzeugt.
     */
    @Override
    public AstNode parse(Parser parser) {
        parser.advance(); // .DEFINE konsumieren

        Token name = parser.consume(TokenType.IDENTIFIER, "Expected a name after .DEFINE.");
        AstNode valueNode = parser.expression();

        if (name != null && valueNode != null) {
            Token valueToken = null;
            if (valueNode instanceof NumberLiteralNode numNode) {
                valueToken = numNode.numberToken();
            } else if (valueNode instanceof TypedLiteralNode typedNode) {
                valueToken = typedNode.value();
            } else if (valueNode instanceof VectorLiteralNode) {
                parser.getDiagnostics().reportError("Vectors cannot be used in .DEFINE directives yet.", "Unknown", name.line());
            }

            if (valueToken != null) {
                parser.getSymbolTable().put(name.text().toUpperCase(), valueToken);
            }
        }

        // .DEFINE erzeugt keinen eigenen Knoten im AST
        return null;
    }
}
