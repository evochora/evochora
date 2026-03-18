package org.evochora.compiler.features.require;

import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.IFeatureRegistrationContext;

/**
 * Compiler feature for the {@code .REQUIRE} directive, which declares unsatisfied
 * module dependencies that must be provided via {@code USING} clauses on the importer's
 * {@code .IMPORT} directive.
 */
public class RequireFeature implements ICompilerFeature {

    @Override
    public String name() {
        return "require";
    }

    @Override
    public void register(IFeatureRegistrationContext ctx) {
        ctx.parserStatement(".REQUIRE", new RequireDirectiveHandler());
        ctx.symbolCollector(RequireNode.class, new RequireSymbolCollector());
        ctx.analysisHandler(RequireNode.class, new RequireAnalysisHandler());
        ctx.irConverter(RequireNode.class, new RequireNodeConverter());
    }
}
