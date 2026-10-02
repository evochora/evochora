package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.api.SourceFile;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.diagnostics.ErrorRecoveryException;
import org.evochora.compiler.util.SourceRootResolver;
import java.util.*;

/**
 * The preprocessor for the assembly language. It runs after the lexer and before the parser.
 * It walks the token stream and hands every token that a handler is registered for to that
 * handler, which rewrites the stream in place.
 */
public class PreProcessor {

    private final List<Token> tokens;
    private final DiagnosticsEngine diagnostics;
    private final SourceRootResolver resolver;
    private int current = 0;
    private final PreProcessorContext ppContext;
    private final BlockReader blockReader;
    private int expansions = 0;
    private final Map<String, Map<String, List<SourceFile.LeftOut>>> leftOut = new LinkedHashMap<>();
    private final Map<String, Map<String, List<SourceFile.Note>>> notes = new LinkedHashMap<>();

    /**
     * Constructs a new PreProcessor.
     *
     * @param initialTokens  The initial list of tokens from the lexer.
     * @param diagnostics    The engine for reporting errors and warnings.
     * @param resolver       The source root resolver for path resolution.
     * @param ppContext      The shared preprocessor context: the handlers to dispatch to, the
     *                       pre-lexed tokens of includable files, the open inclusions.
     */
    public PreProcessor(List<Token> initialTokens, DiagnosticsEngine diagnostics, SourceRootResolver resolver,
                        PreProcessorContext ppContext) {
        this.tokens = new ArrayList<>(initialTokens);
        this.diagnostics = diagnostics;
        this.resolver = resolver;
        this.ppContext = ppContext;
        this.blockReader = new BlockReader(this, ppContext.handlers());
    }

    /**
     * Runs the preprocessor on the token stream. Every token is looked up in the context's
     * handler registry; a token with a handler is handed to it, which rewrites the stream at
     * the current position, and the walk continues from there. A token without one is left as
     * it is, unless it closes or divides a registered block: the handler of a block consumes its
     * closer and dividers, so one the walk reaches stands outside any block and is reported and
     * removed by the {@link BlockReader}.
     * @return The preprocessing result: the expanded tokens, and the regions and notes the
     *         handlers recorded.
     */
    public PreProcessorResult expand() {
        while (current < tokens.size()) {
            Optional<IPreProcessorHandler> handler = ppContext.handlers().get(peek().text());
            if (handler.isPresent()) {
                try {
                    handler.get().process(this, ppContext);
                } catch (ErrorRecoveryException ex) {
                    synchronize();
                }
            } else if (!blockReader.rejectStray(current)) {
                current++;
            }
        }
        return new PreProcessorResult(tokens, leftOut, notes);
    }

    /**
     * Skips the rest of the line a reported error was found on, so the walk resumes with the
     * next line and the handlers see nothing of the directive that failed.
     */
    private void synchronize() {
        while (!isAtEnd()) {
            if (advance().type() == TokenType.NEWLINE) return;
        }
    }

    // --- Records for the source view ---

    /**
     * Opens a new macro expansion and returns its number. The numbers count from 1 within one
     * run of the preprocessor; 0 stands for the text outside any expansion.
     *
     * @return The number of the new expansion.
     */
    public int newExpansion() {
        return ++expansions;
    }

    /**
     * Records a region of lines that was left out, owned by the line of a directive. The region
     * belongs to the placement, file and expansion of the directive token.
     *
     * @param directive The directive token whose line owns the region.
     * @param fromLine  The first line of the region.
     * @param toLine    The last line of the region, inclusive.
     */
    public void leftOut(Token directive, int fromLine, int toLine) {
        SourceInfo at = directive.source();
        leftOut.computeIfAbsent(at.placement(), k -> new LinkedHashMap<>())
                .computeIfAbsent(at.fileName(), k -> new ArrayList<>())
                .add(new SourceFile.LeftOut(at.expansion(), at.lineNumber(), fromLine, toLine));
    }

    /**
     * Records a note at the position of a token, in its placement, file and expansion, and at
     * every position of a parameter the token was substituted for, so that the note stands both
     * where the token was written and where it is used.
     *
     * @param at   The token the note stands at.
     * @param text The text of the note.
     */
    public void note(Token at, String text) {
        noteAt(at.source(), text);
        for (SourceInfo replaced : at.replaces()) {
            noteAt(replaced, text);
        }
    }

    private void noteAt(SourceInfo position, String text) {
        notes.computeIfAbsent(position.placement(), k -> new LinkedHashMap<>())
                .computeIfAbsent(position.fileName(), k -> new ArrayList<>())
                .add(new SourceFile.Note(position.expansion(), position.lineNumber(), position.columnNumber(), text));
    }

    // --- Token stream navigation ---

    /**
     * Checks if the current token matches any of the given types. If so, consumes it.
     * @param types The token types to match.
     * @return true if the current token matches one of the types, false otherwise.
     */
    public boolean match(TokenType... types) {
        for (TokenType type : types) {
            if (check(type)) {
                advance();
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if the current token is of the given type without consuming it.
     * @param type The token type to check.
     * @return true if the current token is of the given type, false otherwise.
     */
    public boolean check(TokenType type) {
        if (isAtEnd()) return false;
        return peek().type() == type;
    }

    /**
     * Consumes the current token and returns the previous one.
     * @return The token before the one that was consumed.
     */
    public Token advance() {
        if (!isAtEnd()) current++;
        return previous();
    }

    /**
     * Returns the current token without consuming it.
     * @return The current token.
     */
    public Token peek() {
        return tokens.get(current);
    }

    /**
     * Returns the previously consumed token.
     * @return The previous token, or null if at the start.
     */
    public Token previous() {
        if (current == 0) return null;
        return tokens.get(current - 1);
    }

    /**
     * Consumes the current token if it is of the expected type. Reports an error otherwise.
     * @param type The expected token type.
     * @param errorMessage The error message if the token type does not match.
     * @return The consumed token.
     * @throws ErrorRecoveryException if the current token does not match the expected type;
     *         the mismatch has been reported to the diagnostics before it is thrown.
     */
    public Token consume(TokenType type, String errorMessage) {
        if (check(type)) return advance();
        Token unexpected = peek();
        getDiagnostics().reportError(errorMessage, unexpected.source().fileName(), unexpected.source().lineNumber());
        throw new ErrorRecoveryException(errorMessage);
    }

    /**
     * Gets the diagnostics engine for reporting errors and warnings.
     * @return The diagnostics engine.
     */
    public DiagnosticsEngine getDiagnostics() {
        return diagnostics;
    }

    /**
     * Checks if the end of the token stream has been reached.
     * @return true if at the end of the stream, false otherwise.
     */
    public boolean isAtEnd() {
        return current >= tokens.size() || tokens.get(current).type() == TokenType.END_OF_FILE;
    }

    // --- Token stream manipulation (used by directive handlers) ---

    /**
     * Injects tokens into the stream at the current position, optionally removing existing tokens first.
     * @param newTokens The tokens to inject.
     * @param tokensToRemove The number of tokens to remove at the current position before injecting.
     */
    public void injectTokens(List<Token> newTokens, int tokensToRemove) {
        int startIndex = current;
        for (int i = 0; i < tokensToRemove; i++) {
            if (startIndex < tokens.size()) tokens.remove(startIndex);
        }
        if (!newTokens.isEmpty() && newTokens.get(newTokens.size() - 1).type() == TokenType.END_OF_FILE) {
            newTokens.remove(newTokens.size() - 1);
        }
        tokens.addAll(startIndex, newTokens);
        this.current = startIndex;
    }

    /**
     * Reads the block whose opener stands at the given index, by the rules of
     * {@link BlockReader#read(int)}: the body begins after the opener's line, blocks of every
     * registered kind nest inside it, and nothing is removed from the stream on success.
     *
     * @param openerIndex The stream index of the token that opens the block.
     * @return The body, the dividers at the block's own level and the index after its closer.
     * @throws ErrorRecoveryException if the block breaks a block rule; the error has been reported.
     */
    public BlockReader.Block readBlock(int openerIndex) {
        return blockReader.read(openerIndex);
    }

    /**
     * Sets the position of the walk, for the {@link BlockReader} to put it back on the opener
     * of a block it cannot read.
     *
     * @param index The stream index to continue at.
     */
    void seek(int index) {
        this.current = index;
    }

    /**
     * Gets the source root resolver for path resolution.
     * @return The source root resolver.
     */
    public SourceRootResolver getResolver() {
        return resolver;
    }

    /**
     * Gets the current index in the token stream.
     * @return The current index.
     */
    public int getCurrentIndex() {
        return current;
    }

    /**
     * Returns the token at the specified index in the stream.
     * @param index The index of the token to retrieve.
     * @return The token at the given index.
     */
    public Token getToken(int index) {
        return tokens.get(index);
    }

    /**
     * Returns the number of tokens in the stream.
     * @return The token count.
     */
    public int streamSize() {
        return tokens.size();
    }

    /**
     * Removes a specified number of tokens from the stream starting at a given index.
     * @param startIndex The starting index.
     * @param count The number of tokens to remove.
     */
    public void removeTokens(int startIndex, int count) {
        if (startIndex < 0 || (startIndex + count) > tokens.size()) {
            throw new IllegalArgumentException("Invalid token removal bounds: startIndex=" + startIndex + ", count=" + count + ", tokens.size()=" + tokens.size());
        }
        tokens.subList(startIndex, startIndex + count).clear();
        this.current = startIndex;
    }
}
