package org.evochora.compiler.features.org;

import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.IFeatureRegistrationContext;

/**
 * Compiler feature for the {@code .ORG} directive, which sets the origin position
 * for subsequent code in the n-dimensional grid.
 */
public class OrgFeature implements ICompilerFeature {

    @Override
    public String name() {
        return "org";
    }

    @Override
    public void register(IFeatureRegistrationContext ctx) {
        // Phase 3: Parsing
        ctx.parser(".ORG", new OrgDirectiveHandler());

        // Phase 7: IR Generation
        ctx.irConverter(OrgNode.class, new OrgNodeConverter());

        // Phase 9: Layout
        ctx.layoutHandler("core", "org", new OrgLayoutHandler());
    }
}
