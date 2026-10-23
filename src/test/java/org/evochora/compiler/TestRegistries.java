package org.evochora.compiler;

import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.expression.OperationRegistry;
import org.evochora.compiler.frontend.postprocess.PostProcessHandlerRegistry;
import org.evochora.compiler.frontend.preprocessor.PreProcessorHandlerRegistry;
import org.evochora.compiler.frontend.semantics.AnalysisHandlerRegistry;
import org.evochora.compiler.model.symbols.SymbolTable;

import java.util.ArrayList;
import java.util.List;

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
     * Builds the operations of computations the standard features register, as {@link Compiler}
     * hands them to the parser.
     */
    public static OperationRegistry operations() {
        FeatureRegistry featureRegistry = new FeatureRegistry(new RuntimeInstructionSetAdapter());
        StandardFeatures.all().forEach(f -> f.register(featureRegistry));
        return featureRegistry.operations();
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
        featureRegistry.preprocessorBlocks().forEach(block ->
                registry.registerBlock(block.kind(), block.handler(), block.stored()));
        featureRegistry.preprocessorTopLevelOnly().forEach(registry::registerTopLevelOnly);
    }

    /**
     * Builds a compiler with the standard features and one feature of the test after them, as a
     * test that looks into a compilation needs it from outside this package.
     */
    public static Compiler compilerWith(ICompilerFeature feature) {
        List<ICompilerFeature> features = new ArrayList<>(StandardFeatures.all());
        features.add(feature);
        return new Compiler(features);
    }
}
