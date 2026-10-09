package org.evochora.compiler.features.control;

import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.IFeatureRegistrationContext;
import org.evochora.compiler.frontend.BlockKind;

import java.util.Set;

/**
 * Consolidates the compiler components of the control block, {@code .CONTROL} … {@code .CASE} …
 * {@code .ENDCONTROL}, into a single feature: the block kind of the parser with its handler, the
 * symbol collectors that make the block a level holding its name, its cases and {@code END}, the
 * analysis handler that enters that level, and the converters that turn the block and its cases
 * into labels around their code.
 */
public class ControlFeature implements ICompilerFeature {

    @Override
    public String name() { return "control"; }

    @Override
    public void register(IFeatureRegistrationContext ctx) {
        ctx.parserBlock(new BlockKind(Set.of(".CONTROL"), ".ENDCONTROL", Set.of(".CASE")), new ControlDirectiveHandler());
        ctx.symbolCollector(ControlNode.class, new ControlSymbolCollector());
        ctx.symbolCollector(ControlCase.class, new ControlCaseSymbolCollector());
        ctx.analysisHandler(ControlNode.class, new ControlAnalysisHandler());
        ctx.irConverter(ControlNode.class, new ControlNodeConverter());
        ctx.irConverter(ControlCase.class, new ControlCaseConverter());
    }
}
