package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.frontend.directive.DirectiveHandlerRegistry;
import org.evochora.compiler.frontend.preprocessor.features.macro.MacroDefinition;
import org.evochora.compiler.frontend.preprocessor.features.repeat.CaretSyntaxTransformer;

import java.nio.file.Path;
import java.util.*;

/**
 * The preprocessor for the assembly language. It runs after the lexer and before the parser.
 * Its main responsibilities are handling file includes and expanding macros.
 * It operates directly on the token stream.
 */
public class PreProcessor implements ParsingContext {

    private final List<Token> tokens;
    private final DiagnosticsEngine diagnostics;
    private final DirectiveHandlerRegistry directiveRegistry;
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
     */
    public PreProcessor(List<Token> initialTokens, DiagnosticsEngine diagnostics, Path basePath) {
        this.tokens = new ArrayList<>(initialTokens);
        this.diagnostics = diagnostics;
        this.basePath = basePath;
        this.directiveRegistry = DirectiveHandlerRegistry.initialize();
    }

    /**
     * Runs the preprocessor on the token stream. It iterates through the tokens,
     * handling directives and expanding macros until no more expansions can be made.
     * @return The final list of tokens after preprocessing.
     */
    public List<Token> expand() {
        // Transform ^n shorthand to .REPEAT n before processing directives
        CaretSyntaxTransformer.transform(tokens);

        while (current < tokens.size()) {
            Token token = peek();
            boolean streamWasModified = false;

            if (token.type() == TokenType.DIRECTIVE) {
                Optional<IDirectiveHandler> handlerOpt = directiveRegistry.get(token.text());
                if (handlerOpt.isPresent() && handlerOpt.get().getPhase() == CompilerPhase.PREPROCESSING) {
                    handlerOpt.get().parse(this, ppContext);
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

    @Override
    public boolean match(TokenType... types) {
        for (TokenType type : types) {
            if (check(type)) {
                advance();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean check(TokenType type) {
        if (isAtEnd()) return false;
        return peek().type() == type;
    }

    @Override
    public Token advance() {
        if (!isAtEnd()) current++;
        return previous();
    }

    @Override
    public Token peek() {
        return tokens.get(current);
    }

    @Override
    public Token previous() {
        if (current == 0) return null;
        return tokens.get(current - 1);
    }

    @Override
    public Token consume(TokenType type, String errorMessage) {
        if (check(type)) return advance();
        Token unexpected = peek();
        getDiagnostics().reportError(errorMessage, unexpected.fileName(), unexpected.line());
        throw new RuntimeException("Parser error: " + errorMessage);
    }

    @Override
    public DiagnosticsEngine getDiagnostics() {
        return diagnostics;
    }

    @Override
    public boolean isAtEnd() {
        return current >= tokens.size() || tokens.get(current).type() == TokenType.END_OF_FILE;
    }

    @Override
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

    @Override
    public Path getBasePath() {
        return basePath;
    }

    @Override
    public boolean hasAlreadyIncluded(String path) {
        return includedFiles.contains(path);
    }

    @Override
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
