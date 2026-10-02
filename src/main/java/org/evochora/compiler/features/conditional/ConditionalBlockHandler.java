package org.evochora.compiler.features.conditional;

import org.evochora.compiler.frontend.preprocessor.BlockReader;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;
import org.evochora.compiler.model.token.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

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
 * <p>
 * A block whose heads are all read and evaluated without error is recorded for the source view
 * through {@link PreProcessor#leftOut} and {@link PreProcessor#note}: the lines of every branch that
 * is not kept, and the state of every flag its heads name. A block removed for an error records
 * nothing, and neither does a block inside a branch that is not kept, which is never processed.
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

        recordForSourceView(preProcessor, flags, heads, conditions, closer, taken);

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

    /**
     * Records for the source view what the block decided: for every branch that is not kept, the
     * lines from its directive line to the next head or the closer, owned by the directive line,
     * unless there are none; for every flag name in every head, the condition's own and one on
     * the right of a comparison, a note with the flag's state as the block saw it. Heads after the
     * kept one were not evaluated, but their flags are noted all the same.
     */
    private static void recordForSourceView(PreProcessor preProcessor, Flags flags, List<DirectiveLine> heads,
                                            List<Condition> conditions, int closer, int taken) {
        for (int k = 0; k < heads.size(); k++) {
            Token word = preProcessor.getToken(heads.get(k).directive());
            if (k != taken) {
                Token next = preProcessor.getToken(k + 1 < heads.size() ? heads.get(k + 1).directive() : closer);
                int from = word.source().lineNumber() + 1;
                int to = next.source().lineNumber() - 1;
                if (from <= to) {
                    preProcessor.leftOut(word, from, to);
                }
            }
            Condition condition = conditions.get(k);
            if (condition == null) {
                continue;
            }
            List<Token> operands = heads.get(k).operands(preProcessor);
            note(preProcessor, flags, operands.get(0));
            if (condition.comparison().isPresent()
                    && condition.comparison().get().operand() instanceof Condition.FlagName) {
                note(preProcessor, flags, operands.get(2));
            }
        }
    }

    /**
     * Notes the state of the flag a token names: {@code [=value]}, {@code [set]} for a flag
     * without a value, {@code [not set]}.
     */
    private static void note(PreProcessor preProcessor, Flags flags, Token name) {
        String state;
        if (!flags.isSet(name.text())) {
            state = "[not set]";
        } else {
            OptionalInt value = flags.valueOf(name.text());
            state = value.isPresent() ? "[=" + value.getAsInt() + "]" : "[set]";
        }
        preProcessor.note(name, state);
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
        return token.source().fileName() + ":" + token.source().lineNumber();
    }

    private static void report(PreProcessor preProcessor, Token at, String message) {
        preProcessor.getDiagnostics().reportError(message, at.source().fileName(), at.source().lineNumber());
    }
}
