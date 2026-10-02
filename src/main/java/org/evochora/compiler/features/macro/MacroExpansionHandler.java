package org.evochora.compiler.features.macro;

import org.evochora.compiler.api.SourceInfo;
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
 * <p>
 * Every expansion gets a number of its own from the {@link Expansions} of the preprocessor run,
 * which the injected body tokens carry, so that the code of one expansion can be told from another's,
 * although all of them stand on the lines of the body. An argument substituted for a parameter
 * keeps its own position and adds the parameter's position in this expansion to the positions
 * it replaces.
 */
public class MacroExpansionHandler implements IPreProcessorHandler {

    private final MacroDefinition macro;

    /**
     * Binds the handler to one macro definition. One instance exists per {@code .MACRO} definition and
     * is registered under that macro's name, so an instance only ever expands this single macro.
     *
     * @param macro The definition supplying the formal parameters and the body tokens substituted at
     *              the invocation site.
     */
    public MacroExpansionHandler(MacroDefinition macro) {
        this.macro = macro;
    }

    @Override
    public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext) {
        int callSiteIndex = preProcessor.getCurrentIndex();
        Token invocation = preProcessor.advance(); // consume macro name

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
                    invocation.source().fileName(), invocation.source().lineNumber());
            preProcessor.removeTokens(callSiteIndex, preProcessor.getCurrentIndex() - callSiteIndex);
            preProcessor.injectTokens(List.of(), 0);
            return;
        }

        // An argument is substituted into a body that was checked when it was read; a block word
        // or a top-level-only directive arriving through it would escape that check.
        for (List<Token> arg : actualArgs) {
            for (Token token : arg) {
                if (preProcessorContext.handlers().isBlockWord(token.text())
                        || preProcessorContext.handlers().isTopLevelOnly(token.text())) {
                    preProcessor.getDiagnostics().reportError(
                            "Macro '" + macro.name().text() + "' cannot take '" + token.text()
                                    + "' as an argument: a block directive or a directive that stands only"
                                    + " at the top level is never an argument.",
                            invocation.source().fileName(), invocation.source().lineNumber());
                    preProcessor.removeTokens(callSiteIndex, preProcessor.getCurrentIndex() - callSiteIndex);
                    return;
                }
            }
        }

        Map<String, List<Token>> argMap = new HashMap<>();
        for (int i = 0; i < macro.parameters().size(); i++) {
            argMap.put(macro.parameters().get(i).text().toUpperCase(), actualArgs.get(i));
        }

        // The body tokens stand in this expansion. An argument keeps its own position, where it
        // was written, and remembers the position of the parameter it replaces in this expansion.
        int expansion = preProcessorContext.getOrCreate(Expansions.class, Expansions::new).next();
        List<Token> expandedBody = new ArrayList<>();
        for (Token bodyToken : macro.body()) {
            SourceInfo position = inExpansion(bodyToken.source(), expansion);
            List<Token> replacement = argMap.get(bodyToken.text().toUpperCase());
            if (replacement == null) {
                expandedBody.add(bodyToken.with(position));
                continue;
            }
            for (Token argument : replacement) {
                List<SourceInfo> replaces = new ArrayList<>(argument.replaces());
                replaces.add(position);
                expandedBody.add(new Token(argument.type(), argument.text(), argument.value(), argument.source(),
                        replaces));
            }
        }

        int removed = 1;
        for (List<Token> g : actualArgs) removed += g.size();
        preProcessor.removeTokens(callSiteIndex, removed);
        preProcessor.injectTokens(expandedBody, 0);
    }

    private static SourceInfo inExpansion(SourceInfo at, int expansion) {
        return new SourceInfo(at.fileName(), at.lineNumber(), at.columnNumber(), at.placement(), expansion);
    }

    /**
     * Returns where the macro was defined: the location of its name in the {@code .MACRO}
     * directive.
     *
     * @return the source location of the definition
     */
    public SourceInfo definedAt() {
        return macro.name().source();
    }

    /**
     * Two handlers are equal when they come from the same definition, the same {@code .MACRO}
     * at the same place in the same file. A file that is included twice defines its macros
     * twice, and those are one definition; a second {@code .MACRO} of the same name anywhere
     * else is a different one, whatever its text.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MacroExpansionHandler other)) return false;
        return Objects.equals(definedAt(), other.definedAt());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(definedAt());
    }
}
