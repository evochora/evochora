package org.evochora.compiler.features.conditional;

import org.evochora.compiler.frontend.DirectiveLine;
import org.evochora.compiler.frontend.lexer.IDependencyScanContext;
import org.evochora.compiler.frontend.lexer.IDependencyScanHandler;

/**
 * Lexer-phase handler for {@code .UNDEF NAME}: removes the flag for the lines read after it. A
 * line the preprocessor would reject, a directive that does not begin a statement included,
 * removes nothing; the preprocessor reports it.
 */
public class UndefScanHandler implements IDependencyScanHandler {

    @Override
    public void handle(DirectiveLine line, IDependencyScanContext ctx) {
        if (!line.startsStatement() || line.operands().size() != 1 || !Condition.isNameToken(line.operands().get(0))) {
            return;
        }
        ctx.flags().undefine(line.operands().get(0).text());
    }
}
