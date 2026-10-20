package org.evochora.compiler.features.source;

import org.evochora.compiler.frontend.lexer.IDependencyInfo;

/**
 * Dependency data for a .SOURCE directive the Lexer phase discovers while it reads the files.
 *
 * @param path The source file path as written in source.
 * @param resolvedPath The resolved absolute path of the source file.
 */
public record SourceDependencyInfo(String path, String resolvedPath) implements IDependencyInfo {
    @Override public String directiveName() { return ".SOURCE"; }
}
