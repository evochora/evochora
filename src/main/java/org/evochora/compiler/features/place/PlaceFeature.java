package org.evochora.compiler.features.place;

import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.IFeatureRegistrationContext;

/**
 * Compiler feature for the {@code .PLACE} directive, which positions literals
 * at specific coordinates in the world using vectors, ranges, or wildcards.
 */
public class PlaceFeature implements ICompilerFeature {

    @Override
    public String name() {
        return "place";
    }

    @Override
    public void register(IFeatureRegistrationContext ctx) {
        ctx.parser(".PLACE", new PlaceDirectiveHandler());
        ctx.irConverter(PlaceNode.class, new PlaceNodeConverter());
        ctx.layoutHandler("core", "place", new PlaceLayoutHandler());
    }
}
