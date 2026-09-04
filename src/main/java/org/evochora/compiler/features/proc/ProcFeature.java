package org.evochora.compiler.features.proc;

import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.IFeatureRegistrationContext;

/**
 * Consolidates all procedure-related compiler components into a single feature.
 * Covers the .PROC directive and CALL statement, including parsing, analysis,
 * post-processing, IR generation, emission rules, linking, and emission contribution.
 */
public class ProcFeature implements ICompilerFeature {

    @Override
    public String name() { return "proc"; }

    @Override
    public void register(IFeatureRegistrationContext ctx) {
        ctx.parserStatement(".PROC", new ProcDirectiveHandler(ctx.isa()));
        ctx.parserStatement("CALL", new CallStatementHandler());
        ctx.symbolCollector(ProcedureNode.class, new ProcedureSymbolCollector());
        ctx.analysisHandler(CallNode.class, new CallAnalysisHandler(ctx.isa()));
        ctx.irConverter(CallNode.class, new CallNodeConverter());
        ctx.analysisHandler(ProcedureNode.class, new ProcedureAnalysisHandler());
        ctx.tokenMapContributor(ProcedureNode.class, new ProcedureTokenMapContributor());
        ctx.irConverter(ProcedureNode.class, new ProcedureNodeConverter());
        ctx.rewriteRule(new ProcedureMarshallingRule());
        ctx.rewriteRule(new CallerMarshallingRule());
        ctx.linkingRule(new CallSiteBindingRule());
        ctx.emissionContributor(new ProcedureEmissionContributor());
    }
}
