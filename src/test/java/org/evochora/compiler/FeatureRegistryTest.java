package org.evochora.compiler;

import org.evochora.compiler.frontend.module.IDependencyScanHandler;
import org.evochora.compiler.frontend.module.IDependencyScanContext;
import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.frontend.semantics.IDependencySetupHandler;
import org.evochora.compiler.frontend.semantics.ModuleSetupContext;
import org.evochora.compiler.frontend.semantics.analysis.IAnalysisHandler;
import org.evochora.compiler.frontend.semantics.analysis.ISymbolCollector;
import org.evochora.compiler.features.importdir.ImportDependencyInfo;
import org.evochora.compiler.frontend.module.IDependencyInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link FeatureRegistry}.
 */
@Tag("unit")
class FeatureRegistryTest {

    private FeatureRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new FeatureRegistry();
    }

    @Test
    void registeredHandlersAreAccessible() {
        IParserStatementHandler statementHandler = ctx -> null;
        IAnalysisHandler analysisHandler = (node, st, diag) -> {};
        ISymbolCollector symbolCollector = (node, st, diag) -> {};

        ICompilerFeature feature = new ICompilerFeature() {
            @Override
            public String name() { return "test"; }

            @Override
            public void register(IFeatureRegistrationContext ctx) {
                ctx.parserStatement(".TEST", statementHandler);
                ctx.analysisHandler(IdentifierNode.class, analysisHandler);
                ctx.symbolCollector(IdentifierNode.class, symbolCollector);
            }
        };

        feature.register(registry);

        assertThat(registry.parserStatementHandlers()).containsValue(statementHandler);
        assertThat(registry.analysisHandlers()).containsValue(analysisHandler);
        assertThat(registry.symbolCollectors()).containsValue(symbolCollector);
    }

    @Test
    void gettersReturnUnmodifiableCollections() {
        IDependencyScanHandler scanHandler = new IDependencyScanHandler() {
            @Override
            public Pattern pattern() { return Pattern.compile(".*"); }
            @Override
            public void handleMatch(Matcher matcher, IDependencyScanContext ctx) {}
        };
        registry.dependencyScanHandler(scanHandler);
        registry.analysisHandler(IdentifierNode.class, (node, st, diag) -> {});

        assertThatThrownBy(() -> registry.parserStatementHandlers().put("X", ctx -> null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> registry.analysisHandlers().put(IdentifierNode.class, (n, s, d) -> {}))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> registry.dependencyScanHandlers().add(scanHandler))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void defaultParserStatementHandler() {
        IParserStatementHandler handler = ctx -> null;
        registry.defaultParserStatement(handler);

        assertThat(registry.defaultParserStatementHandler()).isSameAs(handler);
    }

    @Test
    void dependencyScanHandlers() {
        IDependencyScanHandler handler = new IDependencyScanHandler() {
            @Override
            public Pattern pattern() { return Pattern.compile(".*"); }
            @Override
            public void handleMatch(Matcher matcher, IDependencyScanContext ctx) {}
        };
        registry.dependencyScanHandler(handler);

        assertThat(registry.dependencyScanHandlers()).containsExactly(handler);
    }

    @Test
    void dependencySetupHandlers() {
        IDependencySetupHandler<ImportDependencyInfo> handler = new IDependencySetupHandler<>() {
            @Override
            public void registerScope(ImportDependencyInfo dependency, ModuleSetupContext ctx) {}
        };
        registry.dependencySetupHandler(ImportDependencyInfo.class, handler);

        assertThat(registry.dependencySetupHandlers()).containsKey(ImportDependencyInfo.class);
        assertThat(registry.dependencySetupHandlers().get(ImportDependencyInfo.class)).isSameAs(handler);
    }
}
