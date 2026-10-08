package org.evochora.compiler.features.repeat;

import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.IFeatureRegistrationContext;
import org.evochora.compiler.frontend.BlockKind;

import java.util.Set;

/**
 * Compiler feature for the {@code .REPEAT} directive and its {@code ^} shorthand.
 *
 * <p>Registers the lexer symbol {@code ^}, {@code .REPEAT ... .ENDREPEAT} as a stored block, and
 * two preprocessor handlers, which the preprocessor selects by token text:</p>
 * <ul>
 *   <li>{@code .REPEAT} — repetition of a block of token sequences</li>
 *   <li>{@code ^} — caret shorthand that rewrites into the {@code .REPEAT} block</li>
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
        ctx.preprocessorBlock(new BlockKind(Set.of(".REPEAT"), ".ENDREPEAT", Set.of()), new RepeatDirectiveHandler(), true);
        ctx.preprocessor("^", new CaretDirectiveHandler());
    }
}
