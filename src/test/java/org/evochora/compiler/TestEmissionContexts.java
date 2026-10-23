package org.evochora.compiler;

import org.evochora.compiler.backend.emit.EmissionContext;

import java.util.Map;

/**
 * Builds emission contexts for tests that run emission contributors directly.
 */
public final class TestEmissionContexts {

    private TestEmissionContexts() {}

    /**
     * Builds a context without named layout values, for a program the layout computed no name of.
     *
     * @return The context, with no named layout values.
     */
    public static EmissionContext emissionContext() {
        return new EmissionContext(Map.of());
    }
}
