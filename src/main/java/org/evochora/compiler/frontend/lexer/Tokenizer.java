package org.evochora.compiler.frontend.lexer;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.api.IntegerLiteral;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Turns the characters of one source text into tokens: identifiers, registers, numbers,
 * strings, directives and opcodes by its fixed cases, and the symbols the features registered
 * through {@link org.evochora.compiler.IFeatureRegistrationContext#lexerSymbol(String)}, which it
 * tries first at the start of every token, longest first, and emits as one
 * {@link TokenType#SYMBOL} token whose text is the symbol. The {@link Lexer} phase runs one
 * tokenizer per file it finds.
 */
public class Tokenizer {

    /**
     * The characters the tokenizer reads by its fixed cases: {@code .} begins a directive or a
     * name, {@code -} is the minus, the parentheses, brackets and the comma are tokens of their
     * own, {@code |} and {@code :} are read by the parser, {@code ;} ends a statement, {@code #}
     * begins a comment and {@code "} a string. A registered symbol is tried before these cases, so
     * a symbol equal to one of these characters would take it from the tokenizer;
     * {@link org.evochora.compiler.FeatureRegistry} rejects such a symbol.
     */
    public static final String FIXED_CHARACTERS = ".-()[],|:;#\"";

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
     * Creates a new Tokenizer for the given instruction set, which decides what is an opcode and
     * what is a register. Its tokens carry an empty placement; the preprocessor gives them the
     * placement they stand in.
     * @param source The source code as a single string.
     * @param diagnostics The engine for reporting errors.
     * @param logicalFileName The name of the file being parsed, for error reporting.
     * @param isa The instruction set the source is written for.
     * @param symbols The registered symbols, emitted as {@link TokenType#SYMBOL} tokens. They are
     *                taken as checked by {@link org.evochora.compiler.FeatureRegistry}.
     */
    public Tokenizer(String source, DiagnosticsEngine diagnostics, String logicalFileName, IInstructionSet isa,
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
        tokens.add(new Token(TokenType.END_OF_FILE, "", null,
                new SourceInfo(logicalFileName, line, column, "", 0)));
        return tokens;
    }

    /**
     * Lexes the text of every value of the compilation's options, each on its own, so that the
     * parser can read it where the main program requires the value. A text is lexed with a
     * diagnostics engine of its own: what the lexer rejects is no operand, which the parser's
     * reader of the requirement reports in its own words, and a text whose lexing reported an
     * error yields no tokens. The tokens are kept as the lexer gives them, the end of file among
     * them, and stand under the file name {@code <value NAME>}, line 0, in the root placement, so
     * that a name among them is looked up in the main program. They never enter the token stream.
     *
     * @param values         The text of every value, keyed by its name as the options normalise it.
     * @param rootAliasChain The placement of the compilation root.
     * @param isa            The instruction set the texts are written for.
     * @param symbols        The registered symbols, emitted as {@link TokenType#SYMBOL} tokens.
     * @return The tokens of every value by its name; an empty list for a text the lexer rejected.
     */
    public static Map<String, List<Token>> lexValues(Map<String, String> values, String rootAliasChain,
                                                     IInstructionSet isa, Set<String> symbols) {
        Map<String, List<Token>> valueTokens = new HashMap<>();
        for (Map.Entry<String, String> value : values.entrySet()) {
            String fileName = "<value " + value.getKey() + ">";
            DiagnosticsEngine own = new DiagnosticsEngine();
            List<Token> tokens = new Tokenizer(value.getValue(), own, fileName, isa, symbols).scanTokens();
            valueTokens.put(value.getKey(), own.hasErrors() ? List.of() : tokens.stream()
                    .map(token -> token.with(new SourceInfo(fileName, 0, token.source().columnNumber(), rootAliasChain, 0)))
                    .toList());
        }
        return valueTokens;
    }

    /**
     * Removes the trailing EOF token from a token list, if present. The Lexer phase keeps the
     * tokens of every file without one, so that they can be injected into the stream of the
     * program, which has its own.
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
            case ',': addToken(TokenType.COMMA); break;
            case '(': addToken(touchesWordBefore() ? TokenType.CALL_PARENTHESIS : TokenType.OPEN_PARENTHESIS); break;
            case ')': addToken(TokenType.CLOSE_PARENTHESIS); break;
            case '[': addToken(TokenType.OPEN_BRACKET); break;
            case ']': addToken(TokenType.CLOSE_BRACKET); break;
            case '-': addToken(TokenType.SYMBOL); break;
            case '@':
                // '@' alone is a name of its own; it never joins the word that follows it.
                addToken(TokenType.IDENTIFIER);
                break;
            case ';':
                // Semicolon acts as a statement terminator, allowing multiple instructions per line.
                addToken(TokenType.NEWLINE);
                break;
            case '.': identifier(); break;
            case '#':
                // A comment goes until the end of the line.
                while (peek() != '\n' && !isAtEnd()) advance();
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

    /**
     * Tells whether the {@code (} at the start of the token directly touches the word before it:
     * the character before it belongs to a name and the token emitted last is an identifier or an
     * opcode, as in {@code MIN(} or {@code NOT(}.
     * @return true if the parenthesis opens the arguments of a call.
     */
    private boolean touchesWordBefore() {
        if (start == 0 || !isAlphaNumeric(source.charAt(start - 1)) || tokens.isEmpty()) return false;
        TokenType last = tokens.getLast().type();
        return last == TokenType.IDENTIFIER || last == TokenType.OPCODE;
    }

    private void identifier() {
        // A '.' followed by another '.' ends the name: "A..B" is A, the symbol "..", and B.
        while (isAlphaNumeric(peek()) && !(peek() == '.' && peekNext() == '.')) advance();
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
        // Recognize hex/octal/binary prefixes right at the first digit
        if (previous() == '0' && (peek() == 'x' || peek() == 'X' || peek() == 'o' || peek() == 'O'
                || peek() == 'b' || peek() == 'B')) {
            advance(); // consume 'x', 'o' or 'b'
            // The digits of the base are letters and digits; a '.' ends the number, so that a range
            // such as 0x1F..3 keeps its symbol
            while (isLetterOrDigit(peek())) advance();
        } else {
            // Normal decimal or floating-point numbers
            while (isDigit(peek())) advance();
            if (peek() == '.' && isDigit(peekNext())) {
                advance(); // consume the '.'
                while (isDigit(peek())) advance();
            }
        }

        String numberString = source.substring(start, current);
        OptionalLong value = IntegerLiteral.parse(numberString);
        if (value.isPresent()) {
            addToken(TokenType.NUMBER, value.getAsLong());
        } else {
            diagnostics.reportError("Invalid number format: " + numberString, logicalFileName, line);
        }
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
        tokens.add(new Token(type, text, literal,
                new SourceInfo(logicalFileName, startLine, startColumn, "", 0)));
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

    private static boolean isLetterOrDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
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