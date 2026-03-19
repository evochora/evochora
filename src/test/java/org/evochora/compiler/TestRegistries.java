package org.evochora.compiler;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.postprocess.PostProcessHandlerRegistry;
import org.evochora.compiler.frontend.semantics.AnalysisHandlerRegistry;
import org.evochora.compiler.frontend.semantics.SymbolTable;

/**
 * Builds fully-populated registries for tests, mirroring what {@link Compiler} does.
 * Uses {@link StandardFeatures#all()} as the feature source.
 */
public final class TestRegistries {

    private TestRegistries() {}

    /**
     * Builds a fully populated analysis handler registry for Phase 4 (semantic analysis).
     */
    public static AnalysisHandlerRegistry analysisRegistry(SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        FeatureRegistry featureRegistry = new FeatureRegistry();
        StandardFeatures.all().forEach(f -> f.register(featureRegistry));

        AnalysisHandlerRegistry registry = new AnalysisHandlerRegistry();
        registry.registerAll(featureRegistry.analysisHandlers());
        registry.registerAllCollectors(featureRegistry.symbolCollectors());

        return registry;
    }

    /**
     * Builds a fully populated post-process handler registry for Phase 6 (AST post-processing).
     */
    public static PostProcessHandlerRegistry postProcessRegistry() {
        FeatureRegistry featureRegistry = new FeatureRegistry();
        StandardFeatures.all().forEach(f -> f.register(featureRegistry));

        PostProcessHandlerRegistry registry = new PostProcessHandlerRegistry();
        registry.registerAll(featureRegistry.postProcessHandlers());

        return registry;
    }
}
