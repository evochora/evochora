package org.evochora.compiler.features.source;

import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.IFeatureRegistrationContext;

/**
 * Compiler feature for the {@code .SOURCE} directive.
 *
 * <p>Registers a single preprocessor handler that reads another source file
 * and injects its tokens into the current token stream.</p>
 */
public class SourceFeature implements ICompilerFeature {

    @Override
    public String name() {
        return "source";
    }

    @Override
    public void register(IFeatureRegistrationContext ctx) {
        ctx.dependencyScanHandler(new SourceDependencyScanHandler());
        ctx.preprocessor(".SOURCE", new SourceDirectiveHandler());
    }
}
