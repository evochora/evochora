package org.evochora.compiler.features.macro;

import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.IFeatureRegistrationContext;

/**
 * Compiler feature for the {@code .MACRO} directive system.
 *
 * <p>Registers a single preprocessor handler that parses macro definitions
 * ({@code .MACRO ... .ENDM}) and dynamically registers expansion handlers
 * for each defined macro name.</p>
 */
public class MacroFeature implements ICompilerFeature {

    @Override
    public String name() {
        return "macro";
    }

    @Override
    public void register(IFeatureRegistrationContext ctx) {
        ctx.preprocessor(".MACRO", new MacroDirectiveHandler());
    }
}
