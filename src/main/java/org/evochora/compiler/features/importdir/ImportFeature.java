package org.evochora.compiler.features.importdir;

import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.IFeatureRegistrationContext;
import org.evochora.compiler.features.ctx.CtxFeature;

import java.util.Set;

/**
 * Feature registration for dependency injection between modules. The feature owns both ends of
 * it: {@code .IMPORT} with its {@code USING} clauses, which supplies a dependency, and
 * {@code .REQUIRE}, which declares a dependency the importer must satisfy: a module, or a value
 * that may carry a default.
 *
 * <p>Registers {@code .IMPORT} and {@code .REQUIRE} as directives that may stand only at the top
 * level, and handlers for dependency scanning, module setup, preprocessing (module token inlining),
 * parsing, symbol collection, semantic analysis (USING clause validation, the constants of value
 * requirements, the computations among the values), post-processing (the recorded values of
 * value requirements), IR conversion, layout (the values of value requirements only the layout computes, at the import
 * line or at the requirement's line), and emission (the values of value requirements).
 */
public class ImportFeature implements ICompilerFeature {
    /**
     * {@inheritDoc}
     * <p>
     * Requires {@link CtxFeature}: the module context an imported module is wrapped in.
     */
    @Override
    public Set<Class<? extends ICompilerFeature>> requires() {
        return Set.of(CtxFeature.class);
    }

    @Override
    public String name() {
        return "importdir"; // "import" is a Java reserved word; name matches the package name
    }

    @Override
    public void register(IFeatureRegistrationContext ctx) {
        ctx.dependencyScanHandler(".IMPORT", new ImportDependencyScanHandler());
        ctx.dependencySetupHandler(ImportDependencyInfo.class, new ImportModuleSetupHandler());
        ctx.preprocessorTopLevelOnly(".IMPORT");
        ctx.preprocessor(".IMPORT", new ImportSourceHandler());
        ctx.parserStatement(".IMPORT", new ImportDirectiveHandler());
        ctx.symbolCollector(ImportNode.class, new ImportSymbolCollector());
        ctx.analysisHandler(ImportNode.class, new ImportAnalysisHandler(ctx.operations()));
        ctx.irConverter(ImportNode.class, new ImportNodeConverter());
        ctx.dependencyScanHandler(".REQUIRE", new RequireDependencyScanHandler());
        ctx.preprocessorTopLevelOnly(".REQUIRE");
        ctx.parserStatement(".REQUIRE", new RequireDirectiveHandler());
        ctx.symbolCollector(RequireNode.class, new RequireSymbolCollector());
        ctx.analysisHandler(RequireNode.class, new RequireAnalysisHandler(ctx.operations()));
        ctx.irConverter(RequireNode.class, new RequireNodeConverter());
        ctx.symbolCollector(ValueRequirementNode.class, new RequireSymbolCollector());
        ctx.analysisHandler(ValueRequirementNode.class, new RequireAnalysisHandler(ctx.operations()));
        ctx.analysisHandler(ValueBindingNode.class, new RequireAnalysisHandler(ctx.operations()));
        ctx.postProcessHandler(ValueRequirementNode.class, new ValueRequirementPostProcessHandler());
        ctx.irConverter(ValueRequirementNode.class, new ValueRequirementNodeConverter());
        ctx.layoutHandler("importdir", "bound_value", new BoundValueLayoutHandler());
        ctx.layoutHandler("importdir", "value", new ValueLayoutHandler());
        ctx.emissionContributor(new ValueEmissionContributor());
    }
}
