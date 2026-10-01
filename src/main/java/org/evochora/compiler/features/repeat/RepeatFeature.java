package org.evochora.compiler.features.repeat;

import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.IFeatureRegistrationContext;

/**
 * Compiler feature for the {@code .REPEAT} directive and its {@code ^} shorthand.
 *
 * <p>Registers the lexer symbol {@code ^} and two preprocessor handlers, which the preprocessor
 * selects by token text:</p>
 * <ul>
 *   <li>{@code .REPEAT} — block or inline repetition of token sequences</li>
 *   <li>{@code ^} — caret shorthand that rewrites into {@code .REPEAT} form</li>
 * </ul>
 */
public class RepeatFeature implements ICompilerFeature {

    @Override
    public String name() {
        return "repeat";
    }

    @Override
    public void register(IFeatureRegistrationContext ctx) {
        ctx.lexerSymbol("^");
        ctx.preprocessor(".REPEAT", new RepeatDirectiveHandler());
        ctx.preprocessor("^", new CaretDirectiveHandler());
    }
}
