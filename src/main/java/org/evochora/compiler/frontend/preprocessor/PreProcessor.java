package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.api.SourceFile;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.diagnostics.ErrorRecoveryException;
import org.evochora.compiler.frontend.DirectiveLine;
import org.evochora.compiler.util.SourceRootResolver;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

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
    private final Map<String, Map<String, Set<SourceFile.LeftOut>>> leftOut = new HashMap<>();
    private final Map<String, Map<String, Set<SourceFile.Note>>> notes = new HashMap<>();
    private final List<SourceFile> entries = new ArrayList<>();
    private final Map<Integer, Integer> homes = new HashMap<>();

    /**
     * Constructs a new PreProcessor. The initial tokens are given the placement of the
     * compilation root, the context's alias chain while no inclusion is open, and the context's
     * entry of the main file, if it has one, becomes the first entry.
     *
     * @param initialTokens  The initial list of tokens from the lexer.
     * @param diagnostics    The engine for reporting errors and warnings.
     * @param resolver       The source root resolver for path resolution.
     * @param ppContext      The shared preprocessor context: the handlers to dispatch to, the
     *                       pre-lexed tokens and lines of includable files, the entry of the main
     *                       file, the open inclusions.
     */
    public PreProcessor(List<Token> initialTokens, DiagnosticsEngine diagnostics, SourceRootResolver resolver,
                        PreProcessorContext ppContext) {
        this.tokens = new ArrayList<>(initialTokens.size());
        String root = ppContext.currentAliasChain();
        for (Token token : initialTokens) {
            SourceInfo at = token.source();
            this.tokens.add(root.equals(at.placement()) ? token
                    : token.with(new SourceInfo(at.fileName(), at.lineNumber(), at.columnNumber(), root, at.expansion())));
        }
        this.diagnostics = diagnostics;
        this.resolver = resolver;
        this.ppContext = ppContext;
        this.blockReader = new BlockReader(this, ppContext.handlers());
        if (ppContext.mainFile() != null) {
            entries.add(ppContext.mainFile());
        }
    }

    /**
     * Runs the preprocessor on the token stream. Every token is looked up in the context's
     * handler registry; a token with a handler is handed to it, which rewrites the stream at
     * the current position, and the walk continues from there. A token without one is left as
     * it is, unless it closes or divides a registered block: the handler of a block consumes its
     * closer and dividers, so one the walk reaches stands outside any block and is reported and
     * removed by the {@link BlockReader}.
     * @return The preprocessing result: the expanded tokens, the entries of the inclusions with
     *         the regions and notes the handlers recorded attached to each, and the entry instance
     *         every recorded instance stands on.
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
        List<SourceFile> sources = new ArrayList<>(entries.size());
        for (SourceFile entry : entries) {
            sources.add(entry.withRecords(recordedFor(leftOut, entry, SourceFile.LeftOut::expansion),
                    recordedFor(notes, entry, SourceFile.Note::expansion)));
        }
        return new PreProcessorResult(tokens, sources, homes);
    }

    /**
     * Returns what was recorded for an entry, in the order it was first recorded: the records of
     * its placement and resolved path whose instance is the entry's own or stands on the entry's
     * lines.
     */
    private <T> List<T> recordedFor(Map<String, Map<String, Set<T>>> recorded, SourceFile entry,
                                    ToIntFunction<T> instance) {
        Map<String, Set<T>> byFile = recorded.get(entry.placement());
        if (byFile == null) {
            return List.of();
        }
        return byFile.getOrDefault(entry.resolvedPath(), Set.of()).stream()
                .filter(record -> entryInstanceOf(instance.applyAsInt(record)) == entry.instance())
                .toList();
    }

    /**
     * Returns the instance of the entry whose lines the tokens of an instance stand on: the one
     * {@link #homeOf} recorded for it, or the instance itself.
     */
    private int entryInstanceOf(int instance) {
        return homes.getOrDefault(instance, instance);
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
     * Adds an entry for an inclusion, a file whose tokens are injected into the stream, after the
     * entries added before it. The entry's tokens carry its placement, its resolved path as their
     * file name and its instance as their expansion; the regions and notes recorded at such
     * positions are attached to it when the run ends.
     *
     * @param entry The inclusion, without records.
     */
    public void includes(SourceFile entry) {
        entries.add(entry);
    }

    /**
     * Records that the tokens of an instance stand on the lines of another entry: an instance of
     * injected tokens that is no inclusion of its own, whose positions are copies of positions of
     * text that came in with an inclusion. The regions and notes recorded in the instance are
     * attached to that inclusion's entry, and the result names the entry's instance for it.
     *
     * @param instance The number the injected tokens carry as their expansion.
     * @param position A position of the text the tokens were copied from, as it stood before the
     *                 copy; its placement and file name are those of the instance's tokens.
     */
    public void homeOf(int instance, SourceInfo position) {
        homes.put(instance, entryInstanceOf(position.expansion()));
    }

    /**
     * Records a region of lines that was left out, owned by the line of a directive. The region
     * belongs to the placement, file and instance of injected tokens of the given position. A
     * region equal to one already recorded is recorded once.
     *
     * @param directive The position of the directive whose line owns the region.
     * @param fromLine  The first line of the region.
     * @param toLine    The last line of the region, inclusive.
     */
    public void leftOut(SourceInfo directive, int fromLine, int toLine) {
        leftOut.computeIfAbsent(directive.placement(), k -> new HashMap<>())
                .computeIfAbsent(directive.fileName(), k -> new LinkedHashSet<>())
                .add(new SourceFile.LeftOut(directive.expansion(), directive.lineNumber(), fromLine, toLine));
    }

    /**
     * Records a note at a position, in its placement, file and instance of injected tokens. A
     * note equal to one already recorded is recorded once.
     *
     * @param position The position the note stands at.
     * @param text     The text of the note.
     */
    public void note(SourceInfo position, String text) {
        notes.computeIfAbsent(position.placement(), k -> new HashMap<>())
                .computeIfAbsent(position.fileName(), k -> new LinkedHashSet<>())
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
     * Finds the physical line of the directive at an index, by the rules of
     * {@link DirectiveLine}, with no token before it passed over.
     *
     * @param index The stream index of the directive token.
     * @return The directive's operands, their end, and whether it stands alone on its line.
     */
    public DirectiveLine lineOf(int index) {
        return DirectiveLine.of(tokens, index);
    }

    /**
     * Finds the physical line of the directive at an index, by the rules of
     * {@link DirectiveLine}.
     *
     * @param index      The stream index of the directive token.
     * @param passedOver The tokens directly before the directive that belong to it, which the
     *                   rule passes over.
     * @return The directive's operands, their end, and whether it stands alone on its line.
     */
    public DirectiveLine lineOf(int index, Predicate<Token> passedOver) {
        return DirectiveLine.of(tokens, index, passedOver);
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
