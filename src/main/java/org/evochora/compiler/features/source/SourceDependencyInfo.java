package org.evochora.compiler.features.source;

import org.evochora.compiler.frontend.module.IDependencyInfo;

/**
 * Dependency data for a .SOURCE directive discovered during Phase 0 scanning.
 *
 * @param path The source file path as written in source.
 * @param resolvedPath The resolved absolute path of the source file.
 */
public record SourceDependencyInfo(String path, String resolvedPath) implements IDependencyInfo {
    @Override public String directiveName() { return ".SOURCE"; }
}
