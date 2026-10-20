package org.evochora.compiler.features.conditional;

import org.evochora.compiler.frontend.DirectiveLine;
import org.evochora.compiler.frontend.lexer.IDependencyScanContext;
import org.evochora.compiler.frontend.lexer.IDependencyScanHandler;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.List;
import java.util.OptionalLong;

/**
 * Lexer-phase handler for {@code .DEFINE NAME [integer]}: sets the flag for the lines read after
 * it, so that the files of a conditional block are found as the preprocessor keeps them. A line
 * the preprocessor would reject sets nothing; the preprocessor reports it.
 */
public class DefineScanHandler implements IDependencyScanHandler {

    @Override
    public void handle(DirectiveLine line, IDependencyScanContext ctx) {
        List<Token> operands = line.operands();
        if (!line.standsAlone() || operands.isEmpty() || operands.size() > 2
                || !Condition.isNameToken(operands.get(0))) {
            return;
        }
        OptionalLong value = OptionalLong.empty();
        if (operands.size() == 2) {
            Token number = operands.get(1);
            if (number.type() != TokenType.NUMBER || !(number.value() instanceof Long written)) {
                return;
            }
            value = OptionalLong.of(written);
        }
        Flags.inScan(ctx).define(operands.get(0).text(), value, "at " + ctx.sourcePath() + ":" + ctx.lineNumber());
    }
}
