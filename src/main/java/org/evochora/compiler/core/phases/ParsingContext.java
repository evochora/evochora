package org.evochora.compiler.core.phases;

import org.evochora.compiler.core.Token;
import org.evochora.compiler.core.TokenType;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;

import java.nio.file.Path;
import java.util.List;

/**
 * Ein Interface, das den kontextuellen Zustand während des Parsens oder
 * Präprozessierens kapselt. Es bietet Handlern Zugriff auf den Token-Stream
 * und andere notwendige Dienste, ohne sie direkt an eine spezifische
 * Implementierung wie den Parser zu koppeln.
 */
public interface ParsingContext {
    boolean match(TokenType... types);
    boolean check(TokenType type);
    Token advance();
    Token peek();
    Token previous();
    Token consume(TokenType type, String errorMessage);
    DiagnosticsEngine getDiagnostics();
    boolean isAtEnd();

    // Speziell für den PreProcessor
    void injectTokens(List<Token> tokens, int tokensToRemove);
    Path getBasePath();
    boolean hasAlreadyIncluded(String path);
    void markAsIncluded(String path);
}
