package org.evochora.compiler.features.reg;

import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.IFeatureRegistrationContext;

/**
 * Compiler feature for the {@code .REG} directive, which defines named aliases
 * for data registers and location registers.
 */
public class RegFeature implements ICompilerFeature {

    @Override
    public String name() {
        return "reg";
    }

    @Override
    public void register(IFeatureRegistrationContext ctx) {
        ctx.parserStatement(".REG", new RegDirectiveHandler());
        ctx.analysisHandler(RegNode.class, new RegAnalysisHandler());
        ctx.postProcessHandler(RegNode.class, new RegPostProcessHandler());
        ctx.irConverter(RegNode.class, new RegNodeConverter());
        ctx.emissionContributor(new RegisterAliasEmissionContributor());
    }
}
