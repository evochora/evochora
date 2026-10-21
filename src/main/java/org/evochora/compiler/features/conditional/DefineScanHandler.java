package org.evochora.compiler.features.conditional;

import org.evochora.compiler.frontend.DirectiveLine;
import org.evochora.compiler.frontend.SignedNumber;
import org.evochora.compiler.frontend.lexer.IDependencyScanContext;
import org.evochora.compiler.frontend.lexer.IDependencyScanHandler;
import org.evochora.compiler.model.token.Token;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Lexer-phase handler for {@code .DEFINE NAME [integer]}: sets the flag for the lines read after
 * it, so that the files of a conditional block are found as the preprocessor keeps them. A line
 * the preprocessor would reject, a directive that does not begin a statement included, sets
 * nothing; the preprocessor reports it.
 */
public class DefineScanHandler implements IDependencyScanHandler {

    @Override
    public void handle(DirectiveLine line, IDependencyScanContext ctx) {
        List<Token> operands = line.operands();
        if (!line.startsStatement() || operands.isEmpty() || !Condition.isNameToken(operands.get(0))) {
            return;
        }
        OptionalLong value = OptionalLong.empty();
        if (operands.size() > 1) {
            Optional<SignedNumber> number = SignedNumber.at(operands, 1);
            if (number.isEmpty() || 1 + number.get().length() != operands.size()) {
                return;
            }
            value = OptionalLong.of(number.get().value());
        }
        Flags.inScan(ctx).define(operands.get(0).text(), value, "at " + ctx.sourcePath() + ":" + ctx.lineNumber());
    }
}
