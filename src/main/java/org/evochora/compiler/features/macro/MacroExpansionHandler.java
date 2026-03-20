package org.evochora.compiler.features.macro;

import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Expands a single macro invocation in the token stream. Each instance holds one
 * {@link MacroDefinition} and is dynamically registered by {@link MacroDirectiveHandler}
 * when a {@code .MACRO} definition is encountered during preprocessing.
 */
public class MacroExpansionHandler implements IPreProcessorHandler {

    private final MacroDefinition macro;

    public MacroExpansionHandler(MacroDefinition macro) {
        this.macro = macro;
    }

    @Override
    public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext) {
        int callSiteIndex = preProcessor.getCurrentIndex();
        preProcessor.advance(); // consume macro name

        List<List<Token>> actualArgs = new ArrayList<>();
        while (!preProcessor.isAtEnd() && preProcessor.peek().type() != TokenType.NEWLINE) {
            List<Token> arg = new ArrayList<>();
            Token t = preProcessor.peek();
            if (t.type() == TokenType.IDENTIFIER && (preProcessor.getCurrentIndex() + 2) < preProcessor.streamSize()
                    && preProcessor.getToken(preProcessor.getCurrentIndex() + 1).type() == TokenType.COLON
                    && preProcessor.getToken(preProcessor.getCurrentIndex() + 2).type() == TokenType.NUMBER) {
                arg.add(preProcessor.advance());
                arg.add(preProcessor.advance());
                arg.add(preProcessor.advance());
            } else if (t.type() == TokenType.NUMBER) {
                arg.add(preProcessor.advance());
                while (!preProcessor.isAtEnd() && preProcessor.peek().type() == TokenType.PIPE) {
                    arg.add(preProcessor.advance());
                    if (!preProcessor.isAtEnd()) arg.add(preProcessor.advance());
                    else break;
                }
            } else {
                arg.add(preProcessor.advance());
            }
            actualArgs.add(arg);
        }

        if (actualArgs.size() != macro.parameters().size()) {
            preProcessor.getDiagnostics().reportError(
                    "Macro '" + macro.name().text() + "' expects " + macro.parameters().size()
                            + " arguments, but got " + actualArgs.size(),
                    "preprocessor", macro.name().line());
            preProcessor.removeTokens(callSiteIndex, preProcessor.getCurrentIndex() - callSiteIndex);
            preProcessor.injectTokens(List.of(), 0);
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
        preProcessor.removeTokens(callSiteIndex, removed);
        preProcessor.injectTokens(expandedBody, 0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MacroExpansionHandler other)) return false;
        return Objects.equals(macro, other.macro);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(macro);
    }
}
