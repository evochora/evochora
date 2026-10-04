package org.evochora.compiler.features.conditional;

import org.evochora.compiler.frontend.module.IDependencyScanContext;
import org.evochora.compiler.frontend.module.IDependencyScanHandler;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 0 handler for {@code .UNDEF}: removes the flag from the {@link Flags} of the scan. A
 * line it cannot read is left to the preprocessor's message.
 */
public class UndefScanHandler implements IDependencyScanHandler {

    // The directive word ends where the lexer's directive token ends.
    private static final Pattern UNDEF = Pattern.compile("(?i)^\\.UNDEF(?![A-Za-z0-9_%.$])(.*)$");

    @Override
    public Pattern pattern() {
        return UNDEF;
    }

    @Override
    public void handleMatch(Matcher matcher, IDependencyScanContext ctx) {
        String name = matcher.group(1).trim();
        if (Flags.isName(name)) {
            Flags.inScan(ctx).undefine(name);
        }
    }
}
