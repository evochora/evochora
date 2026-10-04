package org.evochora.compiler.features.constdir;

import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.IFeatureRegistrationContext;

/**
 * Compiler feature for the {@code .CONST} directive, which defines named constants
 * that can be used in place of literal values throughout the source code.
 */
public class ConstFeature implements ICompilerFeature {

    @Override
    public String name() {
        return "constdir"; // "const" is a Java reserved word; name matches the package name
    }

    @Override
    public void register(IFeatureRegistrationContext ctx) {
        // Phase 3: Parsing
        ctx.parserStatement(".CONST", new ConstDirectiveHandler());

        // Phase 4: Semantic Analysis
        ctx.analysisHandler(ConstNode.class, new ConstAnalysisHandler());

        // Phase 7: IR Generation
        ctx.irConverter(ConstNode.class, new ConstNodeConverter());

        // Phase 11: Emission
        ctx.emissionContributor(new ConstantValueEmissionContributor());
    }
}
