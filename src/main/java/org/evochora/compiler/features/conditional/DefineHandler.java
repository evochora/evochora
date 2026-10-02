package org.evochora.compiler.features.conditional;

import org.evochora.compiler.frontend.preprocessor.IPreProcessorHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.List;
import java.util.OptionalInt;

/**
 * Handles {@code .DEFINE NAME [integer]}, which sets a flag in the {@link Flags} of the
 * preprocessor run, from this point of the token stream on. The directive stands alone on its
 * line and is removed with it. A flag that is already set may be defined again only with the
 * same definition.
 * <p>
 * A constant, a value used in code, is a different concept and is written {@code .CONST}; the
 * messages for a {@code .DEFINE} that looks like one say so.
 */
public class DefineHandler implements IPreProcessorHandler {

    private static final String CONSTANT = "; a constant is written .CONST";

    @Override
    public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext) {
        int start = preProcessor.getCurrentIndex();
        Token directive = preProcessor.peek();
        DirectiveLine line = DirectiveLine.at(preProcessor, start);
        Flags flags = Flags.inPreprocessor(preProcessor, preProcessorContext);

        String error = null;
        List<Token> operands = line.operands(preProcessor);
        if (!line.standsAlone(preProcessor)) {
            error = ".DEFINE must stand alone on its line" + CONSTANT;
        } else if (operands.isEmpty()) {
            error = ".DEFINE needs a flag name";
        } else if (!Condition.isNameToken(operands.get(0))) {
            error = ".DEFINE needs a flag name, found '" + operands.get(0).text() + "'" + CONSTANT;
        } else if (operands.size() > 2 || (operands.size() == 2 && !isInteger(operands.get(1)))) {
            error = ".DEFINE takes a flag name and an optional integer" + CONSTANT;
        } else {
            OptionalInt value = operands.size() == 2
                    ? OptionalInt.of((Integer) operands.get(1).value())
                    : OptionalInt.empty();
            error = flags.define(operands.get(0).text(), value,
                    "at " + directive.source().fileName() + ":" + directive.source().lineNumber()).orElse(null);
        }
        if (error != null) {
            preProcessor.getDiagnostics().reportError(error, directive.source().fileName(), directive.source().lineNumber());
        }
        preProcessor.removeTokens(start, line.next(preProcessor) - start);
    }

    private static boolean isInteger(Token token) {
        return token.type() == TokenType.NUMBER && token.value() instanceof Integer;
    }
}
