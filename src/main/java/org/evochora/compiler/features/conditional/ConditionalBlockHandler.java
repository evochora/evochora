package org.evochora.compiler.features.conditional;

import org.evochora.compiler.frontend.preprocessor.BlockReader;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;
import org.evochora.compiler.model.token.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Handles a conditional block, {@code .IFDEF} or {@code .IFNDEF} up to its {@code .ENDDEF},
 * divided by {@code .ELSEIFDEF}, {@code .ELSEIFNDEF} and {@code .ELSEDEF}.
 * <p>
 * The block is read whole through {@link PreProcessor#readBlock(int)} before anything else, so
 * that a block whose head or dividers are malformed is removed and reported once. The conditions
 * of the chain are evaluated in order against the {@link Flags} as they are when the walk reaches
 * the opener; the block is replaced by the lines of the first branch whose condition holds, or by
 * nothing, and the walk continues at the first of those lines. A nested block or a
 * {@code .DEFINE} in the kept branch is therefore processed when the walk reaches it, and a
 * branch that is not kept has no effect at all. The handler keeps nothing between blocks.
 */
public class ConditionalBlockHandler implements IPreProcessorHandler {

    private static final String ELSE = ".ELSEDEF";

    @Override
    public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext) {
        int start = preProcessor.getCurrentIndex();
        Token opener = preProcessor.peek();
        BlockReader.Block block = preProcessor.readBlock(start);
        Flags flags = Flags.inPreprocessor(preProcessor, preProcessorContext);

        int closer = block.end() - 1;
        DirectiveLine closerLine = DirectiveLine.at(preProcessor, closer);
        int blockEnd = closerLine.next(preProcessor);

        List<DirectiveLine> heads = new ArrayList<>();
        heads.add(DirectiveLine.at(preProcessor, start));
        for (int divider : block.dividers()) {
            heads.add(DirectiveLine.at(preProcessor, divider));
        }

        List<Condition> conditions = new ArrayList<>();
        boolean wellFormed = true;
        Token elseSeen = null;
        for (DirectiveLine head : heads) {
            Token word = preProcessor.getToken(head.directive());
            String error = null;
            Condition condition = null;
            if (!head.standsAlone(preProcessor)) {
                error = alone(word);
            } else if (elseSeen != null) {
                error = (isElse(word) ? "A second .ELSEDEF" : upper(word)) + " follows the .ELSEDEF at "
                        + where(elseSeen) + "; .ELSEDEF is the last branch of the block opened at " + where(opener);
            } else if (isElse(word)) {
                elseSeen = word;
                if (!head.operands(preProcessor).isEmpty()) {
                    error = alone(word);
                }
            } else {
                try {
                    condition = Condition.parse(word, head.operands(preProcessor));
                } catch (Condition.Invalid invalid) {
                    error = invalid.getMessage();
                }
            }
            if (error != null) {
                report(preProcessor, word, error);
                wellFormed = false;
            }
            conditions.add(condition);
        }
        if (!closerLine.standsAlone(preProcessor) || !closerLine.operands(preProcessor).isEmpty()) {
            report(preProcessor, preProcessor.getToken(closer), alone(preProcessor.getToken(closer)));
            wellFormed = false;
        }
        if (!wellFormed) {
            preProcessor.removeTokens(start, blockEnd - start);
            return;
        }

        int taken = -1;
        for (int k = 0; k < heads.size() && taken < 0; k++) {
            Condition condition = conditions.get(k);
            try {
                if (condition == null || condition.holds(flags)) {
                    taken = k;
                }
            } catch (Condition.Invalid invalid) {
                report(preProcessor, preProcessor.getToken(heads.get(k).directive()), invalid.getMessage());
                preProcessor.removeTokens(start, blockEnd - start);
                return;
            }
        }

        List<Token> kept = new ArrayList<>();
        if (taken >= 0) {
            int from = heads.get(taken).next(preProcessor);
            int to = taken + 1 < heads.size() ? heads.get(taken + 1).directive() : closer;
            for (int i = from; i < to; i++) {
                kept.add(preProcessor.getToken(i));
            }
        }
        preProcessor.removeTokens(start, blockEnd - start);
        preProcessor.injectTokens(kept, 0);
    }

    private static boolean isElse(Token word) {
        return ELSE.equalsIgnoreCase(word.text());
    }

    private static String alone(Token word) {
        return upper(word) + " must stand alone on its line";
    }

    private static String upper(Token word) {
        return word.text().toUpperCase(Locale.ROOT);
    }

    private static String where(Token token) {
        return token.fileName() + ":" + token.line();
    }

    private static void report(PreProcessor preProcessor, Token at, String message) {
        preProcessor.getDiagnostics().reportError(message, at.fileName(), at.line());
    }
}
