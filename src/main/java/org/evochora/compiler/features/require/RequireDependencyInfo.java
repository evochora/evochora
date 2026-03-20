package org.evochora.compiler.features.require;

import org.evochora.compiler.frontend.module.IDependencyInfo;

/**
 * Dependency data for a .REQUIRE directive discovered during Phase 0 scanning.
 *
 * @param path The required file path as written in source.
 * @param alias The local alias for the required module.
 */
public record RequireDependencyInfo(String path, String alias) implements IDependencyInfo {
    @Override public String directiveName() { return ".REQUIRE"; }
    @Override public boolean allowedInSourceFile() { return false; }
}
