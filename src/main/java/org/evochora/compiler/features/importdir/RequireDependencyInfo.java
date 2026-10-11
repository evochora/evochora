package org.evochora.compiler.features.importdir;

import org.evochora.compiler.frontend.module.IDependencyInfo;

/**
 * A {@code .REQUIRE} line found by the dependency scan. It carries nothing but the directive's
 * name and the rule that a {@code .SOURCE} file may not hold it: what the line declares is the
 * parser's to read.
 */
public record RequireDependencyInfo() implements IDependencyInfo {
    @Override public String directiveName() { return ".REQUIRE"; }
    @Override public boolean allowedInSourceFile() { return false; }
}
