package org.evochora.compiler.core.phases;

import org.evochora.compiler.core.*;
import org.evochora.compiler.core.directives.DirectiveHandlerRegistry;
import org.evochora.compiler.core.directives.IDirectiveHandler;
import org.evochora.compiler.core.directives.MacroDefinition;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;

import java.nio.file.Path;
import java.util.*;

public class PreProcessor implements ParsingContext {

    private final List<Token> tokens;
    private final DiagnosticsEngine diagnostics;
    private final DirectiveHandlerRegistry directiveRegistry;
    private final Path basePath;
    private int current = 0;
    private final Set<String> includedFiles = new HashSet<>();
    private final Map<String, MacroDefinition> macroTable = new HashMap<>();

    public PreProcessor(List<Token> initialTokens, DiagnosticsEngine diagnostics, Path basePath) {
        this.tokens = new ArrayList<>(initialTokens);
        this.diagnostics = diagnostics;
        this.basePath = basePath;
        this.directiveRegistry = DirectiveHandlerRegistry.initialize();
    }

    public List<Token> expand() {
        while(current < tokens.size()) {
            Token token = peek();
            boolean streamWasModified = false;

            if (token.type() == TokenType.DIRECTIVE) {
                Optional<IDirectiveHandler> handlerOpt = directiveRegistry.get(token.text());
                if (handlerOpt.isPresent() && handlerOpt.get().getPhase() == CompilerPhase.PREPROCESSING) {
                    handlerOpt.get().parse(this);
                    streamWasModified = true;
                }
            } else if (token.type() == TokenType.IDENTIFIER) {
                MacroDefinition macro = macroTable.get(token.text().toUpperCase());
                if (macro != null) {
                    expandMacro(macro);
                    streamWasModified = true;
                }
            }

            if (!streamWasModified) {
                current++;
            }
            // If the stream was modified, the handler/expander is responsible for
            // setting the 'current' index correctly. We loop again from there.
        }
        return tokens;
    }

    private void expandMacro(MacroDefinition macro) {
        int callSiteIndex = this.current;
        advance(); // Consume macro name

        List<Token> actualArgs = new ArrayList<>();
        while (!isAtEnd() && peek().type() != TokenType.NEWLINE) {
            actualArgs.add(advance());
        }

        if (actualArgs.size() != macro.parameters().size()) {
            diagnostics.reportError("Macro '" + macro.name().text() + "' expects " + macro.parameters().size() + " arguments, but got " + actualArgs.size(), "preprocessor", macro.name().line());
            this.current = callSiteIndex + 1; // Skip the bad call
            return;
        }

        Map<String, Token> argMap = new HashMap<>();
        for (int i = 0; i < macro.parameters().size(); i++) {
            argMap.put(macro.parameters().get(i).text().toUpperCase(), actualArgs.get(i));
        }

        List<Token> expandedBody = new ArrayList<>();
        for (Token bodyToken : macro.body()) {
            expandedBody.add(argMap.getOrDefault(bodyToken.text().toUpperCase(), bodyToken));
        }

        // Remove macro call (name + arguments)
        int tokensInCall = 1 + actualArgs.size();
        tokens.subList(callSiteIndex, callSiteIndex + tokensInCall).clear();

        // Inject expanded body
        tokens.addAll(callSiteIndex, expandedBody);

        // Reset 'current' to the start of the injected code to re-scan
        this.current = callSiteIndex;
    }

    // --- ParsingContext Implementation ---

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
        getDiagnostics().reportError(errorMessage, "Unknown", unexpected.line());
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

    public void registerMacro(MacroDefinition macro) {
        macroTable.put(macro.name().text().toUpperCase(), macro);
    }

    public int getCurrentIndex() {
        return current;
    }

    public void removeTokens(int startIndex, int count) {
        if (startIndex < 0 || (startIndex + count) > tokens.size()) return;
        tokens.subList(startIndex, startIndex + count).clear();
        this.current = startIndex;
    }
}
