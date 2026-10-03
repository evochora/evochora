package org.evochora.compiler.features.conditional;

import org.evochora.compiler.frontend.module.IDependencyScanContext;
import org.evochora.compiler.frontend.module.IDependencyScanHandler;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 0 handler for the words of a conditional block, so that the dependency scan follows the
 * same branches the preprocessor keeps, with the flags as the scan has met them, and loads no
 * file of a branch the preprocessor removes.
 * <ul>
 *   <li>{@code .IFDEF} / {@code .IFNDEF}: when the condition holds, the block is recorded as open
 *       and the scan continues inside the branch. When it does not, the handler takes the
 *       following lines itself, up to the next divider or {@code .ENDDEF} of the block, nested
 *       conditional blocks counted, and decides there in the same way; {@code .ELSEDEF} always
 *       holds. A line the handler takes is never seen by another scan handler, so nothing in a
 *       branch that is not taken is defined, imported or included.</li>
 *   <li>{@code .ELSEIFDEF}, {@code .ELSEIFNDEF} and {@code .ELSEDEF} reached by the scan end the
 *       branch that was taken: the handler takes the lines up to the block's {@code .ENDDEF}.</li>
 *   <li>{@code .ENDDEF} closes the open block.</li>
 * </ul>
 * A divider or {@code .ENDDEF} with no block open in its file is passed over. A condition the
 * handler cannot read, one that cannot be evaluated, and a head with a word before it on its line
 * make the handler take the lines up to the block's {@code .ENDDEF}, so that no branch of the
 * block is scanned, as the preprocessor removes the whole block. A word before a divider or the
 * {@code .ENDDEF} does not stop the word from counting as one, as the preprocessor matches the
 * block by its words before it checks their lines. The scan reports nothing about conditionals: the preprocessor owns those messages, and an error reported
 * here would stop the compilation before them.
 */
public class ConditionalScanHandler implements IDependencyScanHandler {

    // The directive word begins and ends where the lexer's directive token does. Anything before
    // it on the line, strings closed, is the prefix: a word the preprocessor finds before the
    // directive, which breaks the line rule.
    private static final Pattern WORD = Pattern.compile(
            "(?i)^((?:[^\"]|\"[^\"]*\")*?)(?<![A-Za-z0-9_%.$])"
                    + "(\\.IFDEF|\\.IFNDEF|\\.ELSEIFDEF|\\.ELSEIFNDEF|\\.ELSEDEF|\\.ENDDEF)(?![A-Za-z0-9_%.$])(.*)$");

    private static final String END = ".ENDDEF";
    private static final String ELSE = ".ELSEDEF";

    @Override
    public Pattern pattern() {
        return WORD;
    }

    @Override
    public void handleMatch(Matcher matcher, IDependencyScanContext ctx) {
        String word = matcher.group(2).toUpperCase(Locale.ROOT);
        String file = ctx.sourcePath();
        ScanBlocks blocks = ctx.getOrCreate(ScanBlocks.class, ScanBlocks::new);
        if (isOpener(word)) {
            switch (decide(matcher, ctx)) {
                case HOLDS -> blocks.open(file);
                case FAILS -> seekBranch(ctx, blocks);
                case INVALID -> skipToEnd(ctx);
            }
        } else if (blocks.isOpen(file)) {
            if (!END.equals(word)) {
                skipToEnd(ctx);
            }
            blocks.close(file);
        }
    }

    /**
     * Takes the lines of branches that are not taken, until a branch whose condition holds begins
     * or the block ends.
     */
    private static void seekBranch(IDependencyScanContext ctx, ScanBlocks blocks) {
        int depth = 0;
        String line;
        while ((line = ctx.nextLine()) != null) {
            Matcher matcher = WORD.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String word = matcher.group(2).toUpperCase(Locale.ROOT);
            if (isOpener(word)) {
                depth++;
            } else if (END.equals(word)) {
                if (depth == 0) {
                    return;
                }
                depth--;
            } else if (depth == 0) {
                Decision decision = ELSE.equals(word) && matcher.group(1).isEmpty()
                        ? Decision.HOLDS : decide(matcher, ctx);
                if (decision == Decision.HOLDS) {
                    blocks.open(ctx.sourcePath());
                    return;
                }
                if (decision == Decision.INVALID) {
                    skipToEnd(ctx);
                    return;
                }
            }
        }
    }

    /**
     * Takes the lines up to and including the {@code .ENDDEF} of the block whose branch has ended.
     */
    private static void skipToEnd(IDependencyScanContext ctx) {
        int depth = 0;
        String line;
        while ((line = ctx.nextLine()) != null) {
            Matcher matcher = WORD.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String word = matcher.group(2).toUpperCase(Locale.ROOT);
            if (isOpener(word)) {
                depth++;
            } else if (END.equals(word)) {
                if (depth == 0) {
                    return;
                }
                depth--;
            }
        }
    }

    private enum Decision { HOLDS, FAILS, INVALID }

    /**
     * Decides the head a match of {@link #WORD} found. A head with a word before it is invalid,
     * as the preprocessor finds it so.
     */
    private static Decision decide(Matcher head, IDependencyScanContext ctx) {
        if (!head.group(1).isEmpty()) {
            return Decision.INVALID;
        }
        Optional<Condition> condition = Condition.parse(head.group(2).toUpperCase(Locale.ROOT), head.group(3));
        if (condition.isEmpty()) {
            return Decision.INVALID;
        }
        try {
            return condition.get().holds(Flags.inScan(ctx)) ? Decision.HOLDS : Decision.FAILS;
        } catch (Condition.Invalid invalid) {
            return Decision.INVALID;
        }
    }

    private static boolean isOpener(String word) {
        return ".IFDEF".equals(word) || ".IFNDEF".equals(word);
    }
}
