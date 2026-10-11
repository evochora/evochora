package org.evochora.compiler.features.importdir;

import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.IFeatureRegistrationContext;

/**
 * Feature registration for dependency injection between modules. The feature owns both ends of
 * it: {@code .IMPORT} with its {@code USING} clauses, which supplies a dependency, and
 * {@code .REQUIRE}, which declares a dependency the importer must satisfy.
 *
 * <p>Registers {@code .IMPORT} and {@code .REQUIRE} as directives that may stand only at the top
 * level, and handlers for dependency scanning, module setup, preprocessing (module token inlining),
 * parsing, symbol collection, semantic analysis (USING clause validation), and IR conversion.
 */
public class ImportFeature implements ICompilerFeature {
    @Override
    public String name() {
        return "importdir"; // "import" is a Java reserved word; name matches the package name
    }

    @Override
    public void register(IFeatureRegistrationContext ctx) {
        ctx.dependencyScanHandler(new ImportDependencyScanHandler());
        ctx.dependencySetupHandler(ImportDependencyInfo.class, new ImportModuleSetupHandler());
        ctx.preprocessorTopLevelOnly(".IMPORT");
        ctx.preprocessor(".IMPORT", new ImportSourceHandler());
        ctx.parserStatement(".IMPORT", new ImportDirectiveHandler());
        ctx.symbolCollector(ImportNode.class, new ImportSymbolCollector());
        ctx.analysisHandler(ImportNode.class, new ImportAnalysisHandler());
        ctx.irConverter(ImportNode.class, new ImportNodeConverter());
        ctx.dependencyScanHandler(new RequireDependencyScanHandler());
        ctx.dependencySetupHandler(RequireDependencyInfo.class, new RequireModuleSetupHandler());
        ctx.preprocessorTopLevelOnly(".REQUIRE");
        ctx.parserStatement(".REQUIRE", new RequireDirectiveHandler());
        ctx.symbolCollector(RequireNode.class, new RequireSymbolCollector());
        ctx.analysisHandler(RequireNode.class, new RequireAnalysisHandler());
        ctx.irConverter(RequireNode.class, new RequireNodeConverter());
    }
}
