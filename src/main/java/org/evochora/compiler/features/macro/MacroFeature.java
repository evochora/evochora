package org.evochora.compiler.features.macro;

import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.IFeatureRegistrationContext;
import org.evochora.compiler.frontend.BlockKind;

import java.util.Set;

/**
 * Compiler feature for the {@code .MACRO} directive system.
 *
 * <p>Registers {@code .MACRO ... .ENDMACRO} as a stored block and a single preprocessor handler
 * that parses macro definitions and dynamically registers expansion handlers for each defined
 * macro name.</p>
 */
public class MacroFeature implements ICompilerFeature {

    @Override
    public String name() {
        return "macro";
    }

    @Override
    public void register(IFeatureRegistrationContext ctx) {
        ctx.preprocessorBlock(new BlockKind(Set.of(".MACRO"), ".ENDMACRO", Set.of()), new MacroDirectiveHandler(), true);
    }
}
