package org.evochora.compiler.frontend.module;

import org.evochora.compiler.frontend.semantics.ModuleId;

import java.util.List;

/**
 * Per-module metadata from the dependency scan.
 *
 * @param id          The unique identity of this module.
 * @param sourcePath  The file path or URL.
 * @param content     The raw source text (loaded during the scan).
 * @param imports     Import declarations found in this module.
 * @param requires    Require declarations found in this module.
 * @param sourceFiles Paths of files referenced by {@code .SOURCE} directives (for tracking only).
 */
public record ModuleDescriptor(
        ModuleId id,
        String sourcePath,
        String content,
        List<ImportDecl> imports,
        List<RequireDecl> requires,
        List<String> sourceFiles
) {

    /**
     * An import declaration found during dependency scanning.
     *
     * @param path       The file path or URL being imported (as written in source).
     * @param alias      The local alias name.
     * @param usings     The USING clauses on this import.
     * @param resolvedId The resolved module identity (computed during dependency scanning).
     */
    public record ImportDecl(String path, String alias, List<UsingDecl> usings, ModuleId resolvedId) {
    }

    /**
     * A USING clause on an import declaration.
     *
     * @param sourceAlias The alias being provided (from the importer's scope).
     * @param targetAlias The alias being satisfied (in the imported module's .REQUIRE).
     */
    public record UsingDecl(String sourceAlias, String targetAlias) {}

    /**
     * A require declaration found during dependency scanning.
     *
     * @param path  The required file path or URL.
     * @param alias The local alias name.
     */
    public record RequireDecl(String path, String alias) {}
}
