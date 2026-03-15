package org.evochora.compiler;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.PregNode;
import org.evochora.compiler.frontend.parser.features.importdir.ImportNode;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;
import org.evochora.compiler.frontend.parser.features.require.RequireNode;
import org.evochora.compiler.frontend.postprocess.PostProcessHandlerRegistry;
import org.evochora.compiler.frontend.postprocess.PregPostProcessHandler;
import org.evochora.compiler.frontend.semantics.AnalysisHandlerRegistry;
import org.evochora.compiler.frontend.semantics.SymbolTable;
import org.evochora.compiler.frontend.semantics.analysis.ImportAnalysisHandler;
import org.evochora.compiler.frontend.semantics.analysis.ImportSymbolCollector;
import org.evochora.compiler.frontend.semantics.analysis.InstructionAnalysisHandler;
import org.evochora.compiler.frontend.semantics.analysis.PregAnalysisHandler;
import org.evochora.compiler.frontend.semantics.analysis.ProcedureAnalysisHandler;
import org.evochora.compiler.frontend.semantics.analysis.ProcedureSymbolCollector;
import org.evochora.compiler.frontend.semantics.analysis.RequireAnalysisHandler;
import org.evochora.compiler.frontend.semantics.analysis.RequireSymbolCollector;
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
        registry.registerCollector(ProcedureNode.class, new ProcedureSymbolCollector());
        registry.registerCollector(ImportNode.class, new ImportSymbolCollector());
        registry.registerCollector(RequireNode.class, new RequireSymbolCollector());
        registry.register(ImportNode.class, new ImportAnalysisHandler());
        registry.register(RequireNode.class, new RequireAnalysisHandler());
        registry.register(ProcedureNode.class, new ProcedureAnalysisHandler());
        registry.register(InstructionNode.class, new InstructionAnalysisHandler(symbolTable, diagnostics));
        registry.register(PregNode.class, new PregAnalysisHandler());

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

        // Inline registrations for not-yet-migrated features (mirrors Compiler.java Phase 6)
        registry.register(PregNode.class, new PregPostProcessHandler());

        return registry;
    }
}
