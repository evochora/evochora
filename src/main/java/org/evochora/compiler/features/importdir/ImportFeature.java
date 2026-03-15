package org.evochora.compiler.features.importdir;

import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.IFeatureRegistrationContext;

/**
 * Feature registration for the {@code .IMPORT} directive.
 *
 * <p>Registers handlers for preprocessing (module token inlining), parsing,
 * symbol collection, semantic analysis (USING clause validation), and IR conversion.
 */
public class ImportFeature implements ICompilerFeature {
    @Override
    public String name() {
        return "importdir";
    }

    @Override
    public void register(IFeatureRegistrationContext ctx) {
        ctx.preprocessor(".IMPORT", new ImportSourceHandler());
        ctx.parser(".IMPORT", new ImportDirectiveHandler());
        ctx.symbolCollector(ImportNode.class, new ImportSymbolCollector());
        ctx.analysisHandler(ImportNode.class, new ImportAnalysisHandler());
        ctx.irConverter(ImportNode.class, new ImportNodeConverter());
    }
}
