package org.evochora.compiler;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.postprocess.PostProcessHandlerRegistry;
import org.evochora.compiler.frontend.semantics.AnalysisHandlerRegistry;
import org.evochora.compiler.frontend.semantics.SymbolTable;
import org.evochora.compiler.frontend.semantics.analysis.InstructionAnalysisHandler;
import org.evochora.compiler.model.ast.InstructionNode;

/**
 * Builds fully-populated registries for tests, mirroring what {@link Compiler} does.
 * Uses {@link StandardFeatures#all()} as the feature source, plus inline registrations
 * for features not yet migrated to the feature-slicing architecture.
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

        // Inline registrations for not-yet-migrated features (mirrors Compiler.java Phase 4)
        registry.register(InstructionNode.class, new InstructionAnalysisHandler(symbolTable, diagnostics));

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
