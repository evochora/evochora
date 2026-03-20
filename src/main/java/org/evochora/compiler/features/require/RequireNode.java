package org.evochora.compiler.features.require;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.ISourceLocatable;

/**
 * AST node for the {@code .REQUIRE} directive.
 *
 * <p>Syntax: {@code .REQUIRE "path" AS ALIAS}
 *
 * <p>Declares an unsatisfied dependency on a module. The importer of this module
 * must provide the required module via a {@code USING} clause on the {@code .IMPORT} directive.
 *
 * @param path       The required file path (unquoted string value from the token).
 * @param alias      The local alias name for the required module.
 * @param sourceInfo The source location of the alias token.
 */
public record RequireNode(
        String path,
        String alias,
        SourceInfo sourceInfo
) implements AstNode, ISourceLocatable {
}
