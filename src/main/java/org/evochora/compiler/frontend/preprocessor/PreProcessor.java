package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.model.Token;
import org.evochora.compiler.model.TokenType;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.preprocessor.features.macro.MacroDefinition;
import java.nio.file.Path;
import java.util.*;

/**
 * The preprocessor for the assembly language. It runs after the lexer and before the parser.
 * Its main responsibilities are handling file includes and expanding macros.
 * It operates directly on the token stream.
 */
public class PreProcessor {

    private final List<Token> tokens;
    private final DiagnosticsEngine diagnostics;
    private final PreProcessorDirectiveRegistry directiveRegistry;
    private final Path basePath;
    private int current = 0;
    private final Set<String> includedFiles = new HashSet<>();
    private final PreProcessorContext ppContext = new PreProcessorContext();
    private final Map<String, String> includedFileContents = new HashMap<>();

    /**
     * Constructs a new PreProcessor.
     * @param initialTokens The initial list of tokens from the lexer.
     * @param diagnostics The engine for reporting errors and warnings.
     * @param basePath The base path of the main source file.
     * @param moduleTokens Pre-lexed tokens per imported module (absolute path → token list),
     *                     or null for single-file compilations without imports.
     */
    public PreProcessor(List<Token> initialTokens, DiagnosticsEngine diagnostics, Path basePath,
                        Map<String, List<Token>> moduleTokens) {
        this.tokens = new ArrayList<>(initialTokens);
        this.diagnostics = diagnostics;
        this.basePath = basePath;
        this.directiveRegistry = PreProcessorDirectiveRegistry.initialize(moduleTokens);
    }

    /**
     * Runs the preprocessor on the token stream. It iterates through the tokens,
     * handling directives and expanding macros until no more expansions can be made.
     * @return The final list of tokens after preprocessing.
     */
    public List<Token> expand() {
        while (current < tokens.size()) {
            Token token = peek();
            boolean streamWasModified = false;

            if (token.type() == TokenType.DIRECTIVE) {
                Optional<IPreProcessorDirectiveHandler> handlerOpt = directiveRegistry.get(token.text());
                if (handlerOpt.isPresent()) {
                    handlerOpt.get().process(this, ppContext);
                    streamWasModified = true;
                }
            } else if (token.type() == TokenType.IDENTIFIER) {
                Optional<MacroDefinition> macroOpt = ppContext.getMacro(token.text());
                if (macroOpt.isPresent()) {
                    expandMacro(macroOpt.get());
                    streamWasModified = true;
                }
            }

            if (!streamWasModified) {
                current++;
            }
        }
        return tokens;
    }

    /**
     * Gets the content of all files that were included during preprocessing.
     * @return A map where keys are file paths and values are file contents.
     */
    public Map<String, String> getIncludedFileContents() {
        return includedFileContents;
    }

    /**
     * Adds the content of a source file to the tracking map.
     * @param path The path of the file.
     * @param content The content of the file.
     */
    public void addSourceContent(String path, String content) {
        includedFileContents.put(path, content);
    }

    private void expandMacro(MacroDefinition macro) {
        int callSiteIndex = this.current;
        advance();
        List<List<Token>> actualArgs = new ArrayList<>();
        while (!isAtEnd() && peek().type() != TokenType.NEWLINE) {
            List<Token> arg = new ArrayList<>();
            Token t = peek();
            if (t.type() == TokenType.IDENTIFIER && (current + 2) < tokens.size()
                    && tokens.get(current + 1).type() == TokenType.COLON
                    && tokens.get(current + 2).type() == TokenType.NUMBER) {
                arg.add(advance());
                arg.add(advance());
                arg.add(advance());
            }
            else if (t.type() == TokenType.NUMBER) {
                arg.add(advance());
                while (!isAtEnd() && peek().type() == TokenType.PIPE) {
                    arg.add(advance());
                    if (!isAtEnd()) arg.add(advance());
                    else break;
                }
            } else {
                arg.add(advance());
            }
            actualArgs.add(arg);
        }

        if (actualArgs.size() != macro.parameters().size()) {
            diagnostics.reportError("Macro '" + macro.name().text() + "' expects " + macro.parameters().size() + " arguments, but got " + actualArgs.size(), "preprocessor", macro.name().line());
            this.current = callSiteIndex + 1;
            return;
        }

        Map<String, List<Token>> argMap = new HashMap<>();
        for (int i = 0; i < macro.parameters().size(); i++) {
            argMap.put(macro.parameters().get(i).text().toUpperCase(), actualArgs.get(i));
        }

        List<Token> expandedBody = new ArrayList<>();
        for (Token bodyToken : macro.body()) {
            List<Token> replacement = argMap.get(bodyToken.text().toUpperCase());
            if (replacement != null) expandedBody.addAll(replacement);
            else expandedBody.add(bodyToken);
        }

        int removed = 1;
        for (List<Token> g : actualArgs) removed += g.size();
        tokens.subList(callSiteIndex, callSiteIndex + removed).clear();

        tokens.addAll(callSiteIndex, expandedBody);

        this.current = callSiteIndex;
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
     * Gets the base path of the main source file, used for resolving relative paths.
     * @return The base path.
     */
    public Path getBasePath() {
        return basePath;
    }

    /**
     * Checks if a file has already been included to prevent circular or duplicate inclusion.
     * @param path The path of the file to check.
     * @return true if the file has already been included, false otherwise.
     */
    public boolean hasAlreadyIncluded(String path) {
        return includedFiles.contains(path);
    }

    /**
     * Marks a file as having been included.
     * @param path The path of the file to mark.
     */
    public void markAsIncluded(String path) {
        includedFiles.add(path);
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
     * Removes a specified number of tokens from the stream starting at a given index.
     * @param startIndex The starting index.
     * @param count The number of tokens to remove.
     */
    public void removeTokens(int startIndex, int count) {
        if (startIndex < 0 || (startIndex + count) > tokens.size()) return;
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
