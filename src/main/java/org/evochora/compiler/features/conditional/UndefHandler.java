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
 * error. The directive stands alone on its line and is removed with it.
 */
public class UndefHandler implements IPreProcessorHandler {

    @Override
    public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext) {
        int start = preProcessor.getCurrentIndex();
        Token directive = preProcessor.peek();
        DirectiveLine line = preProcessor.lineOf(start);
        Flags flags = Flags.inPreprocessor(preProcessor, preProcessorContext);

        String error = null;
        List<Token> operands = line.operands();
        if (!line.standsAlone()) {
            error = ".UNDEF must stand alone on its line";
        } else if (operands.isEmpty()) {
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
}
