package org.evochora.compiler.features.label;

import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.IFeatureRegistrationContext;

/**
 * Compiler feature for label definitions ({@code LABEL:} syntax).
 * Registers handlers for symbol collection, semantic analysis, IR generation,
 * and linking (label reference resolution).
 */
public class LabelFeature implements ICompilerFeature {

    @Override
    public String name() { return "label"; }

    @Override
    public void register(IFeatureRegistrationContext ctx) {
        ctx.preprocessor(":", new ColonLabelHandler());
        ctx.parserStatement(".LABEL", new LabelDirectiveHandler());
        ctx.symbolCollector(LabelNode.class, new LabelSymbolCollector());
        ctx.analysisHandler(LabelNode.class, new LabelAnalysisHandler());
        ctx.irConverter(LabelNode.class, new LabelNodeConverter());
        ctx.linkingRule(new LabelRefLinkingRule());
    }
}
