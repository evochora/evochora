package org.evochora.compiler.frontend.module;

/**
 * Marker interface for feature-specific dependency data discovered during Phase 0 scanning.
 * Implemented by feature-specific records (ImportDependencyInfo, RequireDependencyInfo, etc.).
 */
public interface IDependencyInfo {

    /**
     * Returns the directive name that produced this dependency (e.g., ".IMPORT", ".REQUIRE", ".SOURCE").
     * Used for error messages when a directive appears in a forbidden context.
     */
    String directiveName();

    /**
     * Returns whether this dependency type is allowed inside .SOURCE files.
     * Defaults to true. Import and require override to false.
     */
    default boolean allowedInSourceFile() {
        return true;
    }
}
