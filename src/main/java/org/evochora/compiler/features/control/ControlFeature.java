package org.evochora.compiler.features.control;

import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.IFeatureRegistrationContext;
import org.evochora.compiler.frontend.BlockKind;

import java.util.Set;

/**
 * Consolidates the compiler components of the control block, {@code .CONTROL} … {@code .CASE} …
 * {@code .ENDCONTROL}, into a single feature: the block kind of the parser with its handler.
 */
public class ControlFeature implements ICompilerFeature {

    @Override
    public String name() { return "control"; }

    @Override
    public void register(IFeatureRegistrationContext ctx) {
        ctx.parserBlock(new BlockKind(Set.of(".CONTROL"), ".ENDCONTROL", Set.of(".CASE")), new ControlDirectiveHandler());
    }
}
