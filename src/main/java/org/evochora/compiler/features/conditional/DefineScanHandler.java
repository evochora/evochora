package org.evochora.compiler.features.conditional;

import org.evochora.compiler.api.IntegerLiteral;
import org.evochora.compiler.frontend.module.IDependencyScanContext;
import org.evochora.compiler.frontend.module.IDependencyScanHandler;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 0 handler for {@code .DEFINE}: sets the flag in the {@link Flags} of the scan, so that a
 * later conditional block decides on the same flags the preprocessor will see. The scan reports
 * nothing; a line it cannot read, or a definition that conflicts with the flag's first one, is
 * left to the preprocessor's message, and the first definition stays in force.
 */
public class DefineScanHandler implements IDependencyScanHandler {

    // The directive word ends where the lexer's directive token ends.
    private static final Pattern DEFINE = Pattern.compile("(?i)^\\.DEFINE(?![A-Za-z0-9_%.$])(.*)$");
    private static final Pattern OPERANDS = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)(?:\\s+(\\S+))?");

    @Override
    public Pattern pattern() {
        return DEFINE;
    }

    @Override
    public void handleMatch(Matcher matcher, IDependencyScanContext ctx) {
        Matcher operands = OPERANDS.matcher(matcher.group(1).trim());
        if (!operands.matches()) {
            return;
        }
        OptionalInt value = OptionalInt.empty();
        if (operands.group(2) != null) {
            value = IntegerLiteral.parse(operands.group(2));
            if (value.isEmpty()) {
                return;
            }
        }
        Flags.inScan(ctx).define(operands.group(1), value, "at " + ctx.sourcePath() + ":" + ctx.lineNumber());
    }
}
