package org.evochora.compiler.core.phases;

import org.evochora.compiler.core.Token;
import org.evochora.compiler.core.TokenType;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.internal.legacy.NumericParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Der Lexer (auch Tokenizer oder Scanner) ist verantwortlich für die Umwandlung
 * einer Sequenz von Zeichen (Quellcode) in eine Sequenz von Tokens.
 */
public class Lexer {

    private final String source;
    private final DiagnosticsEngine diagnostics;
    private final List<Token> tokens = new ArrayList<>();
    private int start = 0;
    private int current = 0;
    private int line = 1;
    private int column = 1;

    /**
     * Erstellt einen neuen Lexer.
     * @param source Der Quellcode als einzelner String.
     * @param diagnostics Die Engine zum Melden von Fehlern.
     */
    public Lexer(String source, DiagnosticsEngine diagnostics) {
        this.source = source;
        this.diagnostics = diagnostics;

        // TODO: Kann das am ende wieder weg?
        org.evochora.runtime.isa.Instruction.init();
    }

    /**
     * Führt die Tokenisierung des gesamten Quellcodes durch.
     * @return Eine Liste der erkannten Tokens.
     */
    public List<Token> scanTokens() {
        while (!isAtEnd()) {
            start = current;
            scanToken();
        }
        tokens.add(new Token(TokenType.END_OF_FILE, "", null, line, column));
        return tokens;
    }

    private void scanToken() {
        char c = advance();
        switch (c) {
            case '"': string(); break;
            case '|': addToken(TokenType.PIPE); break;
            case ':': addToken(TokenType.COLON); break;
            case '#':
                // Ein Kommentar geht bis zum Ende der Zeile.
                while (peek() != '\n' && !isAtEnd()) advance();
                break;
            case '-':
                // Wenn ein Minus von einer Ziffer gefolgt wird, ist es eine negative Zahl.
                if (isDigit(peek())) {
                    number();
                } else {
                    // Andernfalls ist es ein Fehler (später vielleicht ein Operator).
                    diagnostics.reportError("Unexpected character: " + c, "Unknown", line);
                }
                break;
            // Ignoriere Whitespace
            case ' ', '\r', '\t':
                break;
            case '\n':
                addToken(TokenType.NEWLINE);
                line++;
                column = 1;
                break;
            default:
                if (isDigit(c)) {
                    number();
                } else if (isAlpha(c)) {
                    identifier();
                } else {
                    diagnostics.reportError("Unexpected character: " + c, "Unknown", line);
                }
                break;
        }
    }

    private void identifier() {
        while (isAlphaNumeric(peek())) advance();
        String text = source.substring(start, current);
        TokenType type = TokenType.IDENTIFIER;

        // Ist es ein Register?
        if (text.startsWith("%")) {
            type = TokenType.REGISTER;
        }
        // Ist es eine Direktive?
        else if (text.startsWith(".")) {
            type = TokenType.DIRECTIVE;
        }
        // Ist es ein bekannter Opcode?
        else if (org.evochora.runtime.isa.Instruction.isInstructionName(text)) {
            type = TokenType.OPCODE;
        }

        addToken(type);
    }

    private void number() {
        while (isDigit(peek())) advance();
        // Behandle Dezimalzahlen
        if (peek() == '.' && isDigit(peekNext())) {
            advance(); // den '.' konsumieren
            while (isDigit(peek())) advance();
        }

        String numberString = source.substring(start, current);
        try {
            int value = NumericParser.parseInt(numberString);
            addToken(TokenType.NUMBER, value);
        } catch (NumberFormatException e) {
            diagnostics.reportError("Invalid number format: " + numberString, "Unknown", line);
        }
    }

    private char advance() {
        column++;
        return source.charAt(current++);
    }

    private void string() {
        while (peek() != '"' && !isAtEnd()) {
            if (peek() == '\n') {
                line++;
                column = 1;
            }
            advance();
        }

        if (isAtEnd()) {
            diagnostics.reportError("Unterminated string.", "Unknown", line);
            return;
        }

        // Das schließende "
        advance();

        // Extrahiere den Wert des Strings ohne die Anführungszeichen.
        String value = source.substring(start + 1, current - 1);
        // Der Text des Tokens ist der String *mit* Anführungszeichen, der Wert ist der Inhalt.
        addToken(TokenType.STRING, value, source.substring(start, current));
    }
    private void addToken(TokenType type) {
        addToken(type, null);
    }

    private void addToken(TokenType type, Object literal) {
        String text = source.substring(start, current);
        addToken(type, literal, text);
    }

    private void addToken(TokenType type, Object literal, String text) {
        tokens.add(new Token(type, text, literal, line, start + 1));
    }

    private boolean isAtEnd() {
        return current >= source.length();
    }

    private char peek() {
        if (isAtEnd()) return '\0';
        return source.charAt(current);
    }

    private char peekNext() {
        if (current + 1 >= source.length()) return '\0';
        return source.charAt(current + 1);
    }

    private boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') ||
               (c >= 'A' && c <= 'Z') ||
                c == '_' || c == '%' || c == '.';
    }

    private boolean isAlphaNumeric(char c) {
        return isAlpha(c) || isDigit(c);
    }
}
