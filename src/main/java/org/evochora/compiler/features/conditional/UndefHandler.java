package org.evochora.compiler.features.conditional;

import org.evochora.compiler.frontend.DirectiveLine;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;
import org.evochora.compiler.model.token.Token;

import java.util.List;

/**
 * Handles {@code .UNDEF NAME}, which removes a flag from the {@link Flags} of the preprocessor
 * run, from this point of the token stream on. A flag that is not set stays unset without an
 * error. The directive begins a statement, which the name ends, and is removed with it.
 */
public class UndefHandler implements IPreProcessorHandler {

    @Override
    public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext) {
        int start = preProcessor.getCurrentIndex();
        Token directive = preProcessor.peek();
        DirectiveLine line = preProcessor.lineOf(start);
        Flags flags = PreprocessorFlags.in(preProcessor, preProcessorContext);

        if (!line.startsStatement()) {
            // The directive owns its statement; a word before it without a statement end would lose
            // its own end with the directive's, so the line is reported and left to the parser
            preProcessor.getDiagnostics().reportError(
                    wordBefore(line, directive),
                    directive.source().fileName(), directive.source().lineNumber());
            preProcessor.removeTokens(start, line.end() - start);
            return;
        }

        String error = null;
        List<Token> operands = line.operands();
        if (operands.isEmpty()) {
            error = ".UNDEF needs a flag name";
        } else if (!Condition.isNameToken(operands.get(0))) {
            error = ".UNDEF needs a flag name, found '" + operands.get(0).text() + "'";
        } else if (operands.size() > 1) {
            error = ".UNDEF takes only a flag name, found '" + operands.get(1).text() + "' after it";
        } else {
            flags.undefine(operands.get(0).text());
        }
        if (error != null) {
            preProcessor.getDiagnostics().reportError(error, directive.source().fileName(), directive.source().lineNumber());
        }
        preProcessor.removeTokens(start, line.next() - start);
    }
    /**
     * The message for a word before the directive without a statement end: the parser's own
     * wording when the word is EXPORT, which no flag or inclusion directive takes.
     */
    private static String wordBefore(DirectiveLine line, Token directive) {
        return "EXPORT".equalsIgnoreCase(line.before().text())
                ? "EXPORT is not supported before '" + directive.text() + "'."
                : "Unexpected '" + directive.text() + "' after '" + line.before().text() + "'.";
    }
}
