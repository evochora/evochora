package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.features.importdir.ImportDependencyInfo;
import org.evochora.compiler.features.require.RequireDependencyInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ModuleSetupRegistry}.
 */
@Tag("unit")
class ModuleSetupRegistryTest {

    private ModuleSetupRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ModuleSetupRegistry();
    }

    @Test
    void register_and_resolve() {
        IDependencySetupHandler<ImportDependencyInfo> handler = new IDependencySetupHandler<>() {
            @Override
            public void registerScope(ImportDependencyInfo dependency, ModuleSetupContext ctx) {}
        };
        registry.register(ImportDependencyInfo.class, handler);

        IDependencySetupHandler<ImportDependencyInfo> resolved = registry.resolve(ImportDependencyInfo.class);
        assertThat(resolved).isSameAs(handler);
    }

    @Test
    void resolve_unregisteredReturnsNull() {
        IDependencySetupHandler<ImportDependencyInfo> handler = new IDependencySetupHandler<>() {
            @Override
            public void registerScope(ImportDependencyInfo dependency, ModuleSetupContext ctx) {}
        };
        registry.register(ImportDependencyInfo.class, handler);

        assertThat(registry.resolve(RequireDependencyInfo.class)).isNull();
    }

    @Test
    void typeSafety() {
        IDependencySetupHandler<ImportDependencyInfo> handler = new IDependencySetupHandler<>() {
            @Override
            public void registerScope(ImportDependencyInfo dependency, ModuleSetupContext ctx) {}
        };
        registry.register(ImportDependencyInfo.class, handler);

        IDependencySetupHandler<RequireDependencyInfo> resolved = registry.resolve(RequireDependencyInfo.class);
        assertThat(resolved).isNull();
    }
}
