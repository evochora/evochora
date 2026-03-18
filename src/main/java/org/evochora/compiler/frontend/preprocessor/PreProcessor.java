package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.module.SourceRootResolver;
import java.util.*;
import java.util.Deque;

/**
 * The preprocessor for the assembly language. It runs after the lexer and before the parser.
 * Its main responsibilities are handling file includes and expanding macros.
 * It operates directly on the token stream.
 */
public class PreProcessor {

    private final List<Token> tokens;
    private final DiagnosticsEngine diagnostics;
    private final PreProcessorHandlerRegistry directiveRegistry;
    private final SourceRootResolver resolver;
    private int current = 0;
    private final Deque<String> sourceChain = new ArrayDeque<>();
    private final Deque<String> importChain = new ArrayDeque<>();
    private final PreProcessorContext ppContext;
    private final Map<String, String> includedFileContents = new HashMap<>();

    /**
     * Constructs a new PreProcessor.
     *
     * @param initialTokens  The initial list of tokens from the lexer.
     * @param diagnostics    The engine for reporting errors and warnings.
     * @param resolver       The source root resolver for path resolution.
     * @param registry       The pre-built handler registry for this preprocessing run.
     * @param ppContext       The shared preprocessor context (alias chains, module tokens, etc.).
     */
    public PreProcessor(List<Token> initialTokens, DiagnosticsEngine diagnostics, SourceRootResolver resolver,
                        PreProcessorHandlerRegistry registry, PreProcessorContext ppContext) {
        this.tokens = new ArrayList<>(initialTokens);
        this.diagnostics = diagnostics;
        this.resolver = resolver;
        this.directiveRegistry = registry;
        this.ppContext = ppContext;
    }

    /**
     * Runs the preprocessor on the token stream. It iterates through the tokens,
     * handling directives and expanding macros until no more expansions can be made.
     * @return The preprocessing result containing the expanded tokens and included source contents.
     */
    public PreProcessorResult expand() {
        while (current < tokens.size()) {
            Token token = peek();
            boolean streamWasModified = false;

            if (token.type() == TokenType.DIRECTIVE || token.type() == TokenType.IDENTIFIER) {
                Optional<IPreProcessorHandler> handlerOpt = directiveRegistry.get(token.text());
                if (handlerOpt.isPresent()) {
                    handlerOpt.get().process(this, ppContext);
                    streamWasModified = true;
                }
            }

            if (!streamWasModified) {
                current++;
            }
        }
        return new PreProcessorResult(tokens, includedFileContents);
    }

    /**
     * Adds the content of a source file to the tracking map.
     * @param path The path of the file.
     * @param content The content of the file.
     */
    public void addSourceContent(String path, String content) {
        includedFileContents.put(path, content);
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
     * @throws RuntimeException if the current token does not match the expected type.
     */
    public Token consume(TokenType type, String errorMessage) {
        if (check(type)) return advance();
        Token unexpected = peek();
        getDiagnostics().reportError(errorMessage, unexpected.fileName(), unexpected.line());
        throw new RuntimeException("Parser error: " + errorMessage);
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

    // --- Handler registration (used by directive handlers) ---

    /**
     * Registers a handler at runtime. Used by directive handlers that define new
     * processing rules during compilation (e.g., .MACRO defines expansion handlers).
     *
     * @param name    The token text that triggers this handler.
     * @param handler The handler to register.
     */
    public void registerHandler(String name, IPreProcessorHandler handler) {
        directiveRegistry.register(name, handler);
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
     * Gets the source root resolver for path resolution.
     * @return The source root resolver.
     */
    public SourceRootResolver getResolver() {
        return resolver;
    }

    /**
     * Checks if a file is currently in the .SOURCE inclusion chain (circular detection).
     * Unlike global dedup, this allows the same file to be sourced from different modules.
     * @param path The path of the file to check.
     * @return true if the file is in the current inclusion chain, false otherwise.
     */
    public boolean isInSourceChain(String path) {
        return sourceChain.contains(path);
    }

    /**
     * Pushes a file onto the .SOURCE inclusion chain before inlining its tokens.
     * @param path The path of the file being sourced.
     */
    public void pushSourceChain(String path) {
        sourceChain.push(path);
    }

    /**
     * Pops the top entry from the .SOURCE inclusion chain after inlining completes.
     */
    public void popSourceChain() {
        if (!sourceChain.isEmpty()) sourceChain.pop();
    }

    /**
     * Checks if a module is currently in the .IMPORT inclusion chain (circular detection).
     * @param path The resolved path of the module to check.
     * @return true if the module is in the current inclusion chain, false otherwise.
     */
    public boolean isInImportChain(String path) {
        return importChain.contains(path);
    }

    /**
     * Pushes a module onto the .IMPORT inclusion chain before inlining its tokens.
     * @param path The resolved path of the module being imported.
     */
    public void pushImportChain(String path) {
        importChain.push(path);
    }

    /**
     * Pops the top entry from the .IMPORT inclusion chain after inlining completes.
     */
    public void popImportChain() {
        if (!importChain.isEmpty()) importChain.pop();
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

    /**
     * Gets the shared context for the preprocessor.
     * @return The preprocessor context.
     */
    public PreProcessorContext getPreProcessorContext() {
        return this.ppContext;
    }
}
