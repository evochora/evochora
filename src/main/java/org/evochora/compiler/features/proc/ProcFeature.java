package org.evochora.compiler.features.proc;

import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.IFeatureRegistrationContext;

/**
 * Consolidates all procedure-related compiler components into a single feature.
 * Covers .PROC and .PREG directives, including parsing, analysis, post-processing,
 * IR generation, emission rules, linking, and emission contribution.
 */
public class ProcFeature implements ICompilerFeature {

    @Override
    public String name() { return "proc"; }

    @Override
    public void register(IFeatureRegistrationContext ctx) {
        ctx.parserStatement(".PROC", new ProcDirectiveHandler());
        ctx.parserStatement(".PREG", new PregDirectiveHandler());
        ctx.symbolCollector(ProcedureNode.class, new ProcedureSymbolCollector());
        ctx.analysisHandler(ProcedureNode.class, new ProcedureAnalysisHandler());
        ctx.analysisHandler(PregNode.class, new PregAnalysisHandler());
        ctx.tokenMapContributor(ProcedureNode.class, new ProcedureTokenMapContributor());
        ctx.postProcessHandler(PregNode.class, new PregPostProcessHandler());
        ctx.irConverter(ProcedureNode.class, new ProcedureNodeConverter());
        ctx.irConverter(PregNode.class, new PregNodeConverter());
        ctx.emissionRule(new ProcedureMarshallingRule());
        ctx.emissionRule(new CallerMarshallingRule());
        ctx.linkingRule(new CallSiteBindingRule());
        ctx.emissionContributor(new ProcedureEmissionContributor());
    }
}
