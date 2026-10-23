package org.evochora.compiler.features.conditional;

import org.evochora.compiler.frontend.DirectiveLine;
import org.evochora.compiler.frontend.Export;
import org.evochora.compiler.frontend.Flags;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;
import org.evochora.compiler.model.token.Token;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;

/**
 * Handles {@code .DEFINE NAME [value]} and {@code .DEFINE DEFAULT NAME [value]}, which define a
 * flag in the {@link Flags} of the preprocessor run, from this point of the token stream on. The
 * value is a number, a flag name or a computation, computed now with the flags as they are; one
 * that cannot be computed is reported by the context, and the flag is not set. The directive
 * begins a statement, which the value ends, and is removed with it. A name nothing may define is
 * rejected in both forms.
 * <p>
 * The plain form sets the flag: a flag that is already set may be defined again only with the
 * same definition, and a flag the configuration switched off is rejected. The default form
 * yields to whatever is decided about the flag already: it sets a flag that is free and leaves a
 * flag that is set or switched off as it is.
 * <p>
 * A constant, a value used in code, is a different concept and is written {@code .CONST}; the
 * message for a {@code .DEFINE} that names no flag says so.
 */
public class DefineHandler implements IPreProcessorHandler {

    private static final String CONSTANT = "; a constant is written .CONST";

    @Override
    public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext) {
        int start = preProcessor.getCurrentIndex();
        Token directive = preProcessor.peek();
        DirectiveLine line = preProcessor.lineOf(start);
        Flags flags = preProcessorContext.flags();

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
        boolean isDefault = FlagDefinition.isDefault(line.operands());
        String form = isDefault ? ".DEFINE " + Flags.DEFAULT_OPTION : ".DEFINE";
        List<Token> operands = isDefault
                ? line.operands().subList(1, line.operands().size())
                : line.operands();
        if (operands.isEmpty()) {
            error = form + " needs a flag name";
        } else if (!Condition.isNameToken(operands.get(0))) {
            error = form + " needs a flag name, found '" + operands.get(0).text() + "'" + CONSTANT;
        } else {
            Supplier<Optional<OptionalLong>> value = () -> operands.size() == 1
                    ? Optional.of(OptionalLong.empty())
                    : preProcessorContext.evaluate(operands.subList(1, operands.size())).map(OptionalLong::of);
            String name = operands.get(0).text();
            String origin = "at " + directive.source().fileName() + ":" + directive.source().lineNumber();
            error = (isDefault
                    ? FlagDefinition.defineDefault(flags, name, value, origin)
                    : value.get().flatMap(v -> FlagDefinition.define(flags, name, v, origin))).orElse(null);
        }
        if (error != null) {
            preProcessor.getDiagnostics().reportError(error, directive.source().fileName(), directive.source().lineNumber());
        }
        preProcessor.removeTokens(start, line.next() - start);
    }
}
