package org.evochora.compiler.frontend.module;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;

import java.nio.file.Path;
import java.util.List;

/**
 * Context provided to {@link IDependencyScanHandler} implementations during Phase 0.
 *
 * <p>Allows handlers to record discovered dependencies (imports, requires, source files)
 * and access information about the file currently being scanned.</p>
 */
public interface IDependencyScanContext {

	/**
	 * Records an {@code .IMPORT} dependency.
	 *
	 * @param path   The relative or absolute path of the imported module.
	 * @param alias  The alias under which the module is imported.
	 * @param usings The list of USING clause declarations for selective imports.
	 */
	void addImport(String path, String alias, List<ModuleDescriptor.UsingDecl> usings);

	/**
	 * Records a {@code .REQUIRE} dependency.
	 *
	 * @param path  The relative or absolute path of the required module.
	 * @param alias The alias under which the module is required.
	 */
	void addRequire(String path, String alias);

	/**
	 * Records a {@code .SOURCE} file inclusion.
	 *
	 * @param path The relative or absolute path of the source file to include.
	 */
	void addSourceFile(String path);

	/**
	 * Returns the path of the file currently being scanned.
	 *
	 * @return The source file path.
	 */
	String sourcePath();

	/**
	 * Returns the base directory for resolving relative paths.
	 *
	 * @return The base path of the current source file.
	 */
	Path basePath();

	/**
	 * Returns the diagnostics engine for reporting scan errors and warnings.
	 *
	 * @return The diagnostics engine.
	 */
	DiagnosticsEngine diagnostics();

	/**
	 * Returns the current line number in the file being scanned (1-based).
	 *
	 * @return The line number of the current directive.
	 */
	int lineNumber();
}
