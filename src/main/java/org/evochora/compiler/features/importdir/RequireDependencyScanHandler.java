package org.evochora.compiler.features.importdir;

import org.evochora.compiler.frontend.DirectiveLine;
import org.evochora.compiler.frontend.lexer.IDependencyScanContext;
import org.evochora.compiler.frontend.lexer.IDependencyScanHandler;

/**
 * Lexer-phase handler for {@code .REQUIRE}: records that the file declares a requirement, so
 * that a {@code .SOURCE} file holding one is reported. What the requirement is, the parser reads.
 */
public class RequireDependencyScanHandler implements IDependencyScanHandler {

    @Override
    public void handle(DirectiveLine line, IDependencyScanContext ctx) {
        ctx.addDependency(new RequireDependencyInfo());
    }
}
