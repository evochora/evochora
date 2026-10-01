package org.evochora.compiler.frontend.lexer;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The Lexer (also known as Tokenizer or Scanner) is responsible for converting
 * a sequence of characters (source code) into a sequence of tokens.
 *
 * <p>Besides its fixed cases the lexer knows the symbols the features registered through
 * {@link org.evochora.compiler.IFeatureRegistrationContext#lexerSymbol(String)}. At the start of
 * every token it tries them first, longest first, and emits a match as one
 * {@link TokenType#SYMBOL} token whose text is the symbol.</p>
 */
public class Lexer {

    private final String source;
    private final DiagnosticsEngine diagnostics;
    private final List<Token> tokens = new ArrayList<>();
    private final String logicalFileName;
    private final IInstructionSet isa;
    private final List<String> symbolsLongestFirst;
    private int start = 0;
    private int startLine = 1;   // line of the first character of the token being scanned
    private int startColumn = 1; // 1-based column of that character
    private int current = 0;
    private int line = 1;
    private int column = 1;

    /**
     * Creates a new Lexer for the runtime's instruction set.
     * @param source The source code as a single string.
     * @param diagnostics The engine for reporting errors.
     * @param symbols The registered symbols, emitted as {@link TokenType#SYMBOL} tokens.
     */
    public Lexer(String source, DiagnosticsEngine diagnostics, Set<String> symbols) {
        this(source, diagnostics, "<memory>", symbols);
    }

    /**
     * Creates a new Lexer with an explicit logical file name, for the runtime's instruction set.
     * @param source The source code as a single string.
     * @param diagnostics The engine for reporting errors.
     * @param logicalFileName The name of the file being parsed, for error reporting.
     * @param symbols The registered symbols, emitted as {@link TokenType#SYMBOL} tokens.
     */
    public Lexer(String source, DiagnosticsEngine diagnostics, String logicalFileName, Set<String> symbols) {
        this(source, diagnostics, logicalFileName, new RuntimeInstructionSetAdapter(), symbols);
    }

    /**
     * Creates a new Lexer for the given instruction set, which decides what is an opcode and
     * what is a register.
     * @param source The source code as a single string.
     * @param diagnostics The engine for reporting errors.
     * @param logicalFileName The name of the file being parsed, for error reporting.
     * @param isa The instruction set the source is written for.
     * @param symbols The registered symbols, emitted as {@link TokenType#SYMBOL} tokens. They are
     *                taken as checked by {@link org.evochora.compiler.FeatureRegistry}.
     */
    public Lexer(String source, DiagnosticsEngine diagnostics, String logicalFileName, IInstructionSet isa,
                 Set<String> symbols) {
        this.source = source;
        this.diagnostics = diagnostics;
        this.logicalFileName = logicalFileName;
        this.isa = isa;
        // Longest first, so that a symbol is never cut short by one of its prefixes; equal
        // lengths in a fixed order, so that the order of the set does not matter.
        this.symbolsLongestFirst = symbols.stream()
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    /**
     * Performs the tokenization of the entire source code.
     * @return A list of the recognized tokens.
     */
    public List<Token> scanTokens() {
        while (!isAtEnd()) {
            start = current;
            startLine = line;
            startColumn = column;
            scanToken();
        }
        tokens.add(new Token(TokenType.END_OF_FILE, "", null, line, column, logicalFileName));
        return tokens;
    }

    /**
     * Lexes a set of files that are going to be included into another token stream. Each file
     * is lexed under its own path so its tokens carry that path as their file name, and its
     * trailing EOF token is dropped because the stream it is included into has its own. A file
     * whose text does not end in a newline is lexed as if it did, so its last line ends where
     * the inclusion ends.
     *
     * @param contents    The text of every file, keyed by the path the tokens are to be filed under.
     * @param diagnostics The engine for reporting errors.
     * @param isa         The instruction set the files are written for.
     * @param symbols     The registered symbols, emitted as {@link TokenType#SYMBOL} tokens.
     * @return The tokens of every file under the same key, in the iteration order of the input.
     */
    public static Map<String, List<Token>> lexFiles(Map<String, String> contents, DiagnosticsEngine diagnostics,
                                                    IInstructionSet isa, Set<String> symbols) {
        Map<String, List<Token>> tokensByFile = new LinkedHashMap<>();
        for (Map.Entry<String, String> file : contents.entrySet()) {
            String text = file.getValue();
            if (!text.endsWith("\n")) text += "\n";
            List<Token> tokens = new Lexer(text, diagnostics, file.getKey(), isa, symbols).scanTokens();
            stripEofToken(tokens);
            tokensByFile.put(file.getKey(), tokens);
        }
        return tokensByFile;
    }

    /**
     * Removes the trailing EOF token from a token list, if present.
     * Used when pre-lexed tokens are injected into another token stream
     * that already has its own EOF.
     * @param tokens The token list, trimmed in place and therefore required to be mutable.
     *               An empty list or one not ending in an EOF token is left untouched.
     */
    public static void stripEofToken(List<Token> tokens) {
        if (!tokens.isEmpty() && tokens.getLast().type() == TokenType.END_OF_FILE) {
            tokens.removeLast();
        }
    }

    private void scanToken() {
        if (registeredSymbol()) return;
        char c = advance();
        switch (c) {
            case '"': string(); break;
            case '|': addToken(TokenType.PIPE); break;
            case ':': addToken(TokenType.COLON); break;
            case ';':
                // Semicolon acts as a statement terminator, allowing multiple instructions per line.
                addToken(TokenType.NEWLINE);
                break;
            case '.': identifier(); break;
            case '#':
                // A comment goes until the end of the line.
                while (peek() != '\n' && !isAtEnd()) advance();
                break;
            case '-':
                // If a minus is followed by a digit, it's a negative number.
                if (isDigit(peek())) {
                    number();
                } else {
                    reportUnexpected(c);
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
                    reportUnexpected(c);
                }
                break;
        }
    }

    /**
     * Emits the longest registered symbol that begins at the start of the token, if any.
     * @return true if a symbol was emitted.
     */
    private boolean registeredSymbol() {
        for (String symbol : symbolsLongestFirst) {
            if (source.startsWith(symbol, start)) {
                // A symbol holds no line break, so the column moves with the position.
                current += symbol.length();
                column += symbol.length();
                addToken(TokenType.SYMBOL);
                return true;
            }
        }
        return false;
    }

    /**
     * Reports a character no case and no symbol accepts, naming the registered symbols that
     * begin with it, as the writer most likely meant one of them.
     * @param c The character.
     */
    private void reportUnexpected(char c) {
        List<String> candidates = new ArrayList<>();
        for (String symbol : symbolsLongestFirst) {
            if (symbol.charAt(0) == c) candidates.add("'" + symbol + "'");
        }
        Collections.sort(candidates);
        String message;
        if (candidates.isEmpty()) {
            message = "Unexpected character: " + c;
        } else if (candidates.size() == 1) {
            message = "Unexpected character '" + c + "'; the symbol beginning with it is " + candidates.getFirst();
        } else {
            message = "Unexpected character '" + c + "'; symbols beginning with it are "
                    + String.join(", ", candidates.subList(0, candidates.size() - 1))
                    + " and " + candidates.getLast();
        }
        diagnostics.reportError(message, logicalFileName, line);
    }

    private void identifier() {
        while (isAlphaNumeric(peek())) advance();
        String text = source.substring(start, current);
        TokenType type = TokenType.IDENTIFIER;

        // A word shaped like a register is one, and this is the one place that checks whether
        // source may name it: every later phase takes a REGISTER token as valid.
        Optional<IInstructionSet.RegisterRef> register = text.startsWith("%")
                ? isa.parseRegister(text) : Optional.empty();
        if (register.isPresent()) {
            type = TokenType.REGISTER;
            IInstructionSet.RegisterRef ref = register.get();
            if (!ref.inBounds()) {
                diagnostics.reportError(String.format("Register '%s' is out of bounds. Valid range: %s0-%s%d.",
                        text, ref.bank().prefix(), ref.bank().prefix(), ref.bank().count() - 1), logicalFileName, line);
            } else if (ref.bank().forbidden()) {
                diagnostics.reportError(String.format(
                        "Register '%s' is reserved for procedure parameters. Use the parameter's name instead.", text),
                        logicalFileName, line);
            }
        }
        // Is it a directive?
        else if (text.startsWith(".")) {
            type = TokenType.DIRECTIVE;
        }

        // Is it a known opcode? We check this by trying to get an ID for it.
        else if (isa.getInstructionIdByName(text).isPresent()) {
            type = TokenType.OPCODE;
        }

        addToken(type);
    }
    

    private void number() {
        // After a leading minus the first digit is not yet consumed; consuming it here makes
        // previous() the first digit for signed and unsigned numbers alike.
        if (previous() == '-') advance();
        // Recognize hex/binary prefixes right at the first digit
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

    private char advance() {
        column++;
        return source.charAt(current++);
    }

    private void string() {
        while (peek() != '"' && !isAtEnd()) {
            if (advance() == '\n') {
                line++;
                column = 1;
            }
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
        tokens.add(new Token(type, text, literal, startLine, startColumn, logicalFileName));
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