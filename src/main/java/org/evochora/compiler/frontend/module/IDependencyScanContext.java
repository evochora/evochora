package org.evochora.compiler.frontend.module;

import org.evochora.compiler.util.SourceRootResolver;

import java.io.IOException;

/**
 * Context provided to {@link IDependencyScanHandler} implementations during Phase 0.
 * Offers generic operations for path resolution, content loading, error reporting,
 * and recursive scanning. Handlers use these to implement feature-specific logic
 * without the scanner knowing any directive semantics.
 */
public interface IDependencyScanContext {

    /**
     * Resolves a relative path against the current source file.
     * @param path The relative path from the directive.
     * @return The resolved absolute path.
     * @throws SourceRootResolver.UnknownPrefixException if the path prefix is unknown.
     */
    String resolve(String path) throws SourceRootResolver.UnknownPrefixException;

    /**
     * Loads file content from the given resolved path (filesystem or HTTP).
     * @param resolvedPath The resolved absolute path.
     * @return The file content.
     * @throws IOException if the file cannot be loaded.
     */
    String loadContent(String resolvedPath) throws IOException;

    /**
     * Registers source file content for Phase 1 pre-lexing.
     * @param resolvedPath The resolved absolute path.
     * @param content The file content.
     */
    void registerSourceContent(String resolvedPath, String content);

    /**
     * Reports an error at the current line in the current file.
     * @param message The error message.
     */
    void reportError(String message);

    /**
     * Triggers recursive scanning of an imported module.
     * @param resolvedPath The resolved absolute path.
     * @param content The module content.
     */
    void scanNestedModule(String resolvedPath, String content);

    /**
     * Triggers recursive scanning of a .SOURCE file (for nested .SOURCE detection and validation).
     * @param resolvedPath The resolved absolute path.
     * @param content The source file content.
     */
    void scanNestedSourceFile(String resolvedPath, String content);

    /**
     * Reports a discovered dependency.
     * @param info The feature-specific dependency data.
     */
    void addDependency(IDependencyInfo info);

    /**
     * Returns the path of the file currently being scanned.
     */
    String sourcePath();

    /**
     * Returns the current line number (1-based).
     */
    int lineNumber();
}
