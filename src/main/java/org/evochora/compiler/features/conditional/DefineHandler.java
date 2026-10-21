package org.evochora.compiler.features.conditional;

import org.evochora.compiler.frontend.DirectiveLine;
import org.evochora.compiler.frontend.Export;
import org.evochora.compiler.frontend.SignedNumber;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;
import org.evochora.compiler.model.token.Token;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Handles {@code .DEFINE NAME [integer]}, which sets a flag in the {@link Flags} of the
 * preprocessor run, from this point of the token stream on. The directive begins a statement,
 * which the integer ends, and is removed with it. A flag that is already set may be defined
 * again only with the same definition.
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
        DirectiveLine line = preProcessor.lineOf(start);
        Flags flags = PreprocessorFlags.in(preProcessor, preProcessorContext);

        if (!line.startsStatement()) {
            // The directive owns its statement; a word before it without a statement end would lose
            // its own end with the directive's, so the line is reported and left to the parser
            preProcessor.getDiagnostics().reportError(
                    Export.statementStartMessage(line),
                    directive.source().fileName(), directive.source().lineNumber());
            preProcessor.removeTokens(start, line.end() - start);
            return;
        }

        String error = null;
        List<Token> operands = line.operands();
        Optional<SignedNumber> number = SignedNumber.at(operands, 1);
        if (operands.isEmpty()) {
            error = ".DEFINE needs a flag name";
        } else if (!Condition.isNameToken(operands.get(0))) {
            error = ".DEFINE needs a flag name, found '" + operands.get(0).text() + "'" + CONSTANT;
        } else if (operands.size() > 1 && (number.isEmpty() || 1 + number.get().length() != operands.size())) {
            error = ".DEFINE takes a flag name and an optional integer" + CONSTANT;
        } else {
            OptionalLong value = number.map(n -> OptionalLong.of(n.value())).orElse(OptionalLong.empty());
            error = flags.define(operands.get(0).text(), value,
                    "at " + directive.source().fileName() + ":" + directive.source().lineNumber()).orElse(null);
        }
        if (error != null) {
            preProcessor.getDiagnostics().reportError(error, directive.source().fileName(), directive.source().lineNumber());
        }
        preProcessor.removeTokens(start, line.next() - start);
    }
}
