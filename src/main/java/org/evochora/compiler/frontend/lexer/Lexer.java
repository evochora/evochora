package org.evochora.compiler.frontend.lexer;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * The Lexer (also known as Tokenizer or Scanner) is responsible for converting
 * a sequence of characters (source code) into a sequence of tokens.
 */
public class Lexer {

    private final String source;
    private final DiagnosticsEngine diagnostics;
    private final List<Token> tokens = new ArrayList<>();
    private final String logicalFileName;
    private int start = 0;
    private int current = 0;
    private int line = 1;
    private int column = 1;

    /**
     * Creates a new Lexer.
     * @param source The source code as a single string.
     * @param diagnostics The engine for reporting errors.
     */
    public Lexer(String source, DiagnosticsEngine diagnostics) {
        this(source, diagnostics, "<memory>");
    }

    /**
     * Creates a new Lexer with an explicit logical file name.
     * @param source The source code as a single string.
     * @param diagnostics The engine for reporting errors.
     * @param logicalFileName The name of the file being parsed, for error reporting.
     */
    public Lexer(String source, DiagnosticsEngine diagnostics, String logicalFileName) {
        this.source = source;
        this.diagnostics = diagnostics;
        this.logicalFileName = logicalFileName;
    }

    /**
     * Performs the tokenization of the entire source code.
     * @return A list of the recognized tokens.
     */
    public List<Token> scanTokens() {
        while (!isAtEnd()) {
            start = current;
            scanToken();
        }
        tokens.add(new Token(TokenType.END_OF_FILE, "", null, line, column, logicalFileName));
        return tokens;
    }

    private void scanToken() {
        char c = advance();
        switch (c) {
            case '"': string(); break;
            case ',': addToken(TokenType.COMMA); break;
            case '|': addToken(TokenType.PIPE); break;
            case ':': addToken(TokenType.COLON); break;
            case '*': addToken(TokenType.STAR); break;
            case '^': addToken(TokenType.DIRECTIVE); break;
            case ';':
                // Semicolon acts as a statement terminator, allowing multiple instructions per line.
                addToken(TokenType.NEWLINE);
                break;
            case '.':
                if (peek() == '.') {
                    advance(); // Consume the second dot
                    addToken(TokenType.DOT_DOT);
                } else {
                    identifier();
                }
                break;
            case '#':
                // A comment goes until the end of the line.
                while (peek() != '\n' && !isAtEnd()) advance();
                break;
            case '-':
                // If a minus is followed by a digit, it's a negative number.
                if (isDigit(peek())) {
                    number();
                } else {
                    // Otherwise, it's an error (maybe an operator later).
                    diagnostics.reportError("Unexpected character: " + c, logicalFileName, line);
                }
                break;
            // Ignore whitespace
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
                    diagnostics.reportError("Unexpected character: " + c, logicalFileName, line);
                }
                break;
        }
    }

    private void identifier() {
        while (isAlphaNumeric(peek())) advance();
        String text = source.substring(start, current);
        TokenType type = TokenType.IDENTIFIER;

        // Is it a register? Only treat valid register patterns as REGISTER tokens
        if (text.startsWith("%") && isValidRegisterPattern(text)) {
            type = TokenType.REGISTER;
        }
        // Is it a directive?
        else if (text.startsWith(".")) {
            type = TokenType.DIRECTIVE;
        }

        // Is it a known opcode? We check this by trying to get an ID for it.
        else if (org.evochora.runtime.isa.Instruction.getInstructionIdByName(text) != null) {
            type = TokenType.OPCODE;
        }

        addToken(type);
    }
    
    /**
     * Checks if a token represents a valid register pattern.
     * Valid patterns are: %DRx, %PRx, %FPRx, %LRx where x is a number.
     * 
     * @param text the token text to check
     * @return true if the text represents a valid register pattern
     */
    private boolean isValidRegisterPattern(String text) {
        if (!text.startsWith("%")) {
            return false;
        }
        
        // Check for valid register patterns
        if (text.matches("%DR\\d+")) return true;  // %DR0, %DR1, etc.
        if (text.matches("%PR\\d+")) return true;  // %PR0, %PR1, etc.
        if (text.matches("%FPR\\d+")) return true; // %FPR0, %FPR1, etc.
        if (text.matches("%LR\\d+")) return true;  // %LR0, %LR1, etc.
        
        // Not a valid register pattern
        return false;
    }

    private void number() {
        // Recognize hex/binary prefixes right at the start of a number
        if (previous() == '0' && (peek() == 'x' || peek() == 'X' || peek() == 'b' || peek() == 'B')) {
            advance(); // consume 'x' or 'b'
            while (isAlphaNumeric(peek())) advance(); // Hex digits (A-F) are also alphanumeric
        } else {
            // Normal decimal or floating-point numbers
            while (isDigit(peek())) advance();
            if (peek() == '.' && isDigit(peekNext())) {
                advance(); // consume the '.'
                while (isDigit(peek())) advance();
            }
        }

        String numberString = source.substring(start, current);
        try {
            int value = parseInt(numberString);
            addToken(TokenType.NUMBER, value);
        } catch (NumberFormatException e) {
            diagnostics.reportError("Invalid number format: " + numberString, logicalFileName, line);
        }
    }

    // *** START OF CORRECTION: The logic of NumericParser is now here. ***
    private int parseInt(String token) throws NumberFormatException {
        if (token == null) throw new NumberFormatException("null");
        String s = token.trim();
        boolean negative = false;

        if (s.startsWith("+")) {
            s = s.substring(1);
        } else if (s.startsWith("-")) {
            negative = true;
            s = s.substring(1);
        }

        int radix = 10;
        if (s.startsWith("0b") || s.startsWith("0B")) {
            radix = 2;
            s = s.substring(2);
        } else if (s.startsWith("0x") || s.startsWith("0X")) {
            radix = 16;
            s = s.substring(2);
        } else if (s.startsWith("0o") || s.startsWith("0O")) {
            radix = 8;
            s = s.substring(2);
        }

        if (s.isEmpty()) throw new NumberFormatException("Empty numeric literal");
        int value = Integer.parseInt(s, radix);
        return negative ? -value : value;
    }
    // *** END OF CORRECTION ***

    private char advance() {
        column++;
        return source.charAt(current++);
    }

    // ... Rest of the class remains unchanged ...

    private void string() {
        while (peek() != '"' && !isAtEnd()) {
            if (peek() == '\n') {
                line++;
                column = 1;
            }
            advance();
        }

        if (isAtEnd()) {
            diagnostics.reportError("Unterminated string.", logicalFileName, line);
            return;
        }

        // The closing "
        advance();

        // Extract the value of the string without the quotes.
        String value = source.substring(start + 1, current - 1);
        // The text of the token is the string *with* quotes, the value is the content.
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
        tokens.add(new Token(type, text, literal, line, start + 1, logicalFileName));
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
                c == '_' || c == '%' || c == '.' || c == '$';
    }

    private boolean isAlphaNumeric(char c) {
        return isAlpha(c) || isDigit(c);
    }

    private char previous() {
        return source.charAt(current - 1);
    }
}