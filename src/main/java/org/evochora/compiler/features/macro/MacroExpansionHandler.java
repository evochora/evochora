package org.evochora.compiler.features.macro;

import org.evochora.compiler.api.Expansion;
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

/**
 * Expands a single macro invocation in the token stream. Each instance holds one
 * {@link MacroDefinition} and is dynamically registered by {@link MacroDirectiveHandler}
 * when a {@code .MACRO} definition is encountered during preprocessing.
 * <p>
 * Every expansion gets an instance number of its own from the preprocessor context
 * ({@link PreProcessorContext#nextInstance()}), which the injected body tokens carry, so that
 * the code of one expansion can be told from another's, although all of them stand on the lines
 * of the body. An argument substituted for a parameter keeps its own position and adds the
 * parameter's position in this expansion to the positions it replaces.
 * <p>
 * An expansion is no entry of the preprocessor's sources: the handler reports it to the
 * preprocessor with the position of the definition as it stood when it was read, whose placement,
 * file and expansion name the entry or the enclosing expansion holding the body's lines, the
 * position of the call, the macro's name and the arguments bound to its parameters.
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
        List<Expansion.Binding> bindings = new ArrayList<>(macro.parameters().size());
        for (int i = 0; i < macro.parameters().size(); i++) {
            Token parameter = macro.parameters().get(i);
            argMap.put(parameter.text().toUpperCase(), actualArgs.get(i));
            bindings.add(new Expansion.Binding(parameter.text(), asWritten(actualArgs.get(i))));
        }

        // The body tokens stand in this expansion. An argument keeps its own position, where it
        // was written, and remembers the position of the parameter it replaces in this expansion.
        // A body token that is itself an argument an enclosing expansion substituted, in a macro
        // defined in that body, is treated the same way: it keeps its position and remembers its
        // position in the body, now in this expansion. A call whose name was substituted so is
        // called at its position in the body, so that the expansion stands in the enclosing one.
        int expansion = preProcessorContext.nextInstance();
        preProcessor.expands(expansion, definedAt(), inBody(invocation), macro.name().text(), bindings);
        List<Token> expandedBody = new ArrayList<>();
        for (Token bodyToken : macro.body()) {
            SourceInfo position = inExpansion(inBody(bodyToken), expansion);
            List<Token> replacement = argMap.get(bodyToken.text().toUpperCase());
            if (replacement == null) {
                expandedBody.add(bodyToken.replaces().isEmpty() ? bodyToken.with(position)
                        : replacing(bodyToken, position));
                continue;
            }
            for (Token argument : replacement) {
                expandedBody.add(replacing(argument, position));
            }
        }

        int removed = 1;
        for (List<Token> g : actualArgs) removed += g.size();
        preProcessor.removeTokens(callSiteIndex, removed);
        preProcessor.injectTokens(expandedBody, 0);
    }

    /**
     * Returns the text of an argument as it was written: its tokens, with a space between two that
     * did not touch on their line.
     */
    private static String asWritten(List<Token> argument) {
        StringBuilder text = new StringBuilder();
        Token previous = null;
        for (Token token : argument) {
            boolean touches = previous != null && previous.source().lineNumber() == token.source().lineNumber()
                    && previous.source().columnNumber() + previous.text().length() == token.source().columnNumber();
            if (previous != null && !touches) {
                text.append(' ');
            }
            text.append(token.text());
            previous = token;
        }
        return text.toString();
    }

    /**
     * Returns where a token stands in the text it was read from: the last position it replaced,
     * for a token substituted into that text, otherwise its own position.
     */
    private static SourceInfo inBody(Token token) {
        return token.replaces().isEmpty() ? token.source() : token.replaces().getLast();
    }

    /**
     * Returns a token that keeps its own position and remembers one more position it replaces.
     */
    private static Token replacing(Token token, SourceInfo position) {
        List<SourceInfo> replaces = new ArrayList<>(token.replaces());
        replaces.add(position);
        return new Token(token.type(), token.text(), token.value(), token.source(), replaces);
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
     * Returns the definition this handler expands.
     *
     * @return the definition the handler was created with
     */
    public MacroDefinition definition() {
        return macro;
    }
}
