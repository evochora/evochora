package org.evochora.compiler.features.repeat;

import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.List;

/**
 * Finds the tokens of a repeat count in the stream: one value, a number with its sign, a flag
 * name or a computation, as the preprocessor context reads it. What follows the value on the line
 * is not part of it, unless it begins with an operator: then the whole rest of the line is taken,
 * so that computing it reports the operator, which needs parentheses there as everywhere.
 */
final class RepeatCount {

    private RepeatCount() {
    }

    /**
     * Returns the tokens of the count that begins at a stream index.
     *
     * @param preProcessor        The preprocessor holding the stream.
     * @param preProcessorContext The context that knows the forms of a value.
     * @param from                The stream index the count begins at.
     * @return The tokens of the count, in a list of their own; empty if no count begins there.
     */
    static List<Token> at(PreProcessor preProcessor, PreProcessorContext preProcessorContext, int from) {
        int lineEnd = from;
        while (lineEnd < preProcessor.streamSize() && !ends(preProcessor.getToken(lineEnd))) {
            lineEnd++;
        }
        List<Token> line = preProcessor.tokensOf(from, lineEnd);
        if (line.isEmpty()) {
            return line;
        }
        int length = preProcessorContext.valueLength(line);
        if (length > 0 && length < line.size() && line.get(length).type() == TokenType.SYMBOL) {
            return line;
        }
        return line.subList(0, length);
    }

    private static boolean ends(Token token) {
        return token.type() == TokenType.NEWLINE || token.type() == TokenType.END_OF_FILE;
    }
}
