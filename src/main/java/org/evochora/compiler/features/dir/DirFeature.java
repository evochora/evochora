package org.evochora.compiler.features.dir;

import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.IFeatureRegistrationContext;

/**
 * Compiler feature for the {@code .DIR} directive, which sets the default direction
 * for subsequent instructions in the n-dimensional grid.
 */
public class DirFeature implements ICompilerFeature {

    @Override
    public String name() {
        return "dir";
    }

    @Override
    public void register(IFeatureRegistrationContext ctx) {
        // Phase 3: Parsing
        ctx.parserStatement(".DIR", new DirDirectiveHandler());

        // Phase 7: IR Generation
        ctx.irConverter(DirNode.class, new DirNodeConverter());

        // Phase 9: Layout
        ctx.layoutHandler("core", "dir", new DirLayoutHandler());
    }
}
