package org.evochora.compiler.features.conditional;

import org.evochora.compiler.frontend.DirectiveLine;
import org.evochora.compiler.frontend.lexer.IDependencyScanContext;
import org.evochora.compiler.frontend.lexer.IDependencyScanHandler;
import org.evochora.compiler.model.token.Token;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;

/**
 * Lexer-phase handler for {@code .DEFINE NAME [value]} and {@code .DEFINE DEFAULT NAME [value]}:
 * defines the flag for the lines read after it by the rules the preprocessor applies, its value
 * computed as the preprocessor computes it, so that the files of a conditional block are found as
 * the preprocessor keeps them. The plain form sets the flag and is rejected for a flag the
 * configuration switched off; the default form yields to whatever is decided about the flag
 * already. A line the preprocessor would reject, a directive that does not begin a statement and
 * a value that cannot be computed included, sets nothing; the preprocessor reports it.
 */
public class DefineScanHandler implements IDependencyScanHandler {

    @Override
    public void handle(DirectiveLine line, IDependencyScanContext ctx) {
        boolean isDefault = FlagDefinition.isDefault(line.operands());
        List<Token> operands = isDefault
                ? line.operands().subList(1, line.operands().size())
                : line.operands();
        if (!line.startsStatement() || operands.isEmpty() || !Condition.isNameToken(operands.get(0))) {
            return;
        }
        Supplier<Optional<OptionalLong>> value = () -> operands.size() == 1
                ? Optional.of(OptionalLong.empty())
                : ctx.evaluate(operands.subList(1, operands.size())).map(OptionalLong::of);
        String name = operands.get(0).text();
        String origin = "at " + ctx.sourcePath() + ":" + ctx.lineNumber();
        if (isDefault) {
            FlagDefinition.defineDefault(ctx.flags(), name, value, origin);
        } else {
            value.get().ifPresent(v -> FlagDefinition.define(ctx.flags(), name, v, origin));
        }
    }
}
