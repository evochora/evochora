package org.evochora.compiler.features.define;

import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.IFeatureRegistrationContext;

/**
 * Compiler feature for the {@code .DEFINE} directive, which defines named constants
 * that can be used in place of literal values throughout the source code.
 */
public class DefineFeature implements ICompilerFeature {

    @Override
    public String name() {
        return "define";
    }

    @Override
    public void register(IFeatureRegistrationContext ctx) {
        // Phase 3: Parsing
        ctx.parser(".DEFINE", new DefineDirectiveHandler());

        // Phase 4: Semantic Analysis
        ctx.analysisHandler(DefineNode.class, new DefineAnalysisHandler());

        // Phase 6: AST Post-Processing
        ctx.postProcessHandler(DefineNode.class, new DefinePostProcessHandler());

        // Phase 7: IR Generation
        ctx.irConverter(DefineNode.class, new DefineNodeConverter());
    }
}
