package org.evochora.compiler.frontend.module;

import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.util.SourceRootResolver;

import java.io.IOException;
import java.util.function.Supplier;

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
     * @return The path as it was resolved; errors reported through {@link #reportError}
     *         are attributed to this file.
     */
    String sourcePath();

    /**
     * Returns the current line number (1-based).
     * @return The number of the line last handed out in the current file, counted from one: the
     *         line the handler was invoked for, or the line last taken through {@link #nextLine()}.
     */
    int lineNumber();

    /**
     * Takes the next line of the file being scanned, read as the scanner reads every line: the
     * text after a {@code #} removed, surrounding whitespace trimmed, and a line left empty
     * skipped. A line taken here is not offered to the handlers again; the scan continues after
     * it once the handler returns.
     *
     * @return The next non-empty line, or {@code null} at the end of the file.
     */
    String nextLine();

    /**
     * Returns the options of the compilation.
     * @return The options the scanner was created with.
     */
    CompilerOptions options();

    /**
     * Returns the state object a feature keeps under the given key type, creating it with the
     * factory on the first request. One instance exists per key for the whole scan, across every
     * file it reads; the core knows nothing of the type and never reads the object.
     *
     * @param key     The class used as the key.
     * @param factory Creates the state object; called only while no object exists for the key.
     * @param <T>     The type of the state object.
     * @return The existing or newly created state object.
     */
    <T> T getOrCreate(Class<T> key, Supplier<T> factory);
}
