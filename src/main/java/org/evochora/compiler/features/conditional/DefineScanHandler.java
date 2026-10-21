package org.evochora.compiler.features.conditional;

import org.evochora.compiler.frontend.DirectiveLine;
import org.evochora.compiler.frontend.lexer.IDependencyScanContext;
import org.evochora.compiler.frontend.lexer.IDependencyScanHandler;
import org.evochora.compiler.model.token.Token;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Lexer-phase handler for {@code .DEFINE NAME [value]}: sets the flag for the lines read after
 * it, its value computed as the preprocessor computes it, so that the files of a conditional
 * block are found as the preprocessor keeps them. A line the preprocessor would reject, a
 * directive that does not begin a statement and a value that cannot be computed included, sets
 * nothing; the preprocessor reports it.
 */
public class DefineScanHandler implements IDependencyScanHandler {

    @Override
    public void handle(DirectiveLine line, IDependencyScanContext ctx) {
        List<Token> operands = line.operands();
        if (!line.startsStatement() || operands.isEmpty() || !Condition.isNameToken(operands.get(0))) {
            return;
        }
        Optional<OptionalLong> value = operands.size() == 1
                ? Optional.of(OptionalLong.empty())
                : ctx.evaluate(operands.subList(1, operands.size())).map(OptionalLong::of);
        value.ifPresent(v -> ctx.flags().define(operands.get(0).text(), v,
                "at " + ctx.sourcePath() + ":" + ctx.lineNumber()));
    }
}
