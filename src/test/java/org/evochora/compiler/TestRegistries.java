package org.evochora.compiler;

import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.postprocess.PostProcessHandlerRegistry;
import org.evochora.compiler.frontend.preprocessor.PreProcessorHandlerRegistry;
import org.evochora.compiler.frontend.semantics.AnalysisHandlerRegistry;
import org.evochora.compiler.model.symbols.SymbolTable;

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
        FeatureRegistry featureRegistry = new FeatureRegistry(new RuntimeInstructionSetAdapter());
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
        FeatureRegistry featureRegistry = new FeatureRegistry(new RuntimeInstructionSetAdapter());
        StandardFeatures.all().forEach(f -> f.register(featureRegistry));

        PostProcessHandlerRegistry registry = new PostProcessHandlerRegistry();
        registry.registerAll(featureRegistry.postProcessHandlers());

        return registry;
    }

    /**
     * Registers the block kinds and the top-level-only directives of the standard features into a
     * preprocessor registry, as {@link Compiler} does before Phase 2. The handlers are left to the
     * test, which registers the ones it exercises.
     */
    public static void registerPreProcessorBlocks(PreProcessorHandlerRegistry registry) {
        FeatureRegistry featureRegistry = new FeatureRegistry(new RuntimeInstructionSetAdapter());
        StandardFeatures.all().forEach(f -> f.register(featureRegistry));
        featureRegistry.preprocessorBlocks().forEach(registry::registerBlock);
        featureRegistry.preprocessorTopLevelOnly().forEach(registry::registerTopLevelOnly);
    }
}
