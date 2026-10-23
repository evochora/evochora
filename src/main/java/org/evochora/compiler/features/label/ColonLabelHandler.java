package org.evochora.compiler.features.label;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.frontend.Export;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;

import java.util.List;
import java.util.Objects;

/**
 * Rewrites label syntax {@code NAME:} to {@code .LABEL NAME} in the token stream.
 * This allows the parser to handle labels via the standard statement registry
 * instead of hardcoded syntax detection.
 *
 * <p>Triggered when the preprocessor encounters a {@code :} (COLON) token. An identifier before
 * the colon is a label when it begins a statement: the token directly before it is a statement
 * end, the start of the stream, or a token of another file or line, such as the marker an
 * inclusion puts before a file's first line or the separator a repetition puts between two
 * bodies; an {@code EXPORT} before the name is passed over by the same rule. Anywhere else the
 * colon belongs to a typed literal ({@code CODE:5}, {@code STATE:-1}) and is left alone.</p>
 */
public class ColonLabelHandler implements IPreProcessorHandler {

    @Override
    public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext) {
        int colonIndex = preProcessor.getCurrentIndex();
        int nameIndex = colonIndex - 1;
        if (nameIndex < 0 || preProcessor.getToken(nameIndex).type() != TokenType.IDENTIFIER
                || !beginsStatement(preProcessor, nameIndex)) {
            preProcessor.advance();
            return;
        }

        // Rewrite: replace IDENTIFIER COLON with .LABEL IDENTIFIER
        Token identToken = preProcessor.getToken(nameIndex);
        Token syntheticLabel = new Token(TokenType.DIRECTIVE, ".LABEL", null, identToken.source());

        preProcessor.removeTokens(nameIndex, 2); // remove IDENTIFIER + COLON
        preProcessor.injectTokens(List.of(syntheticLabel, identToken), 0);
    }

    /**
     * Tells whether the token at an index begins a statement, an {@code EXPORT} directly before
     * it passed over.
     */
    private static boolean beginsStatement(PreProcessor preProcessor, int index) {
        if (follows(preProcessor, index)) {
            return true;
        }
        return Export.isWord(preProcessor.getToken(index - 1)) && follows(preProcessor, index - 1);
    }

    /**
     * Tells whether the token at an index follows a boundary: it is the first of the stream, or
     * the token before it is a statement end or stands in another file or on another line.
     */
    private static boolean follows(PreProcessor preProcessor, int index) {
        if (index == 0) {
            return true;
        }
        Token before = preProcessor.getToken(index - 1);
        SourceInfo beforeAt = standsAt(before);
        SourceInfo at = standsAt(preProcessor.getToken(index));
        return before.type() == TokenType.NEWLINE
                || beforeAt.lineNumber() != at.lineNumber()
                || !Objects.equals(beforeAt.fileName(), at.fileName());
    }

    /**
     * Returns where a token stands in the text being read: for an argument a macro substituted,
     * the position of the parameter it replaced, so that a typed literal passed to a macro stands
     * on the line of the body; otherwise the token's own position.
     */
    private static SourceInfo standsAt(Token token) {
        return token.replaces().isEmpty() ? token.source() : token.replaces().getLast().source();
    }
}
