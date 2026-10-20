package org.evochora.compiler.features.source;

import org.evochora.compiler.frontend.DirectiveLine;
import org.evochora.compiler.frontend.lexer.IDependencyScanContext;
import org.evochora.compiler.frontend.lexer.IDependencyScanHandler;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.util.SourceRootResolver;

import java.io.IOException;

/**
 * Lexer-phase handler for {@code .SOURCE "path"}: reads the file so that its own directives are
 * followed, and records the dependency. A line that is not exactly the directive and one path
 * names no file here; the preprocessor reports what is wrong with it.
 */
public class SourceDependencyScanHandler implements IDependencyScanHandler {

    @Override
    public void handle(DirectiveLine line, IDependencyScanContext ctx) {
        if (!line.standsAlone() || line.operands().size() != 1
                || line.operands().get(0).type() != TokenType.STRING) {
            return;
        }
        Token pathToken = line.operands().get(0);
        String path = (String) pathToken.value();
        String resolvedPath;
        try {
            resolvedPath = ctx.resolve(path);
        } catch (SourceRootResolver.UnknownPrefixException e) {
            ctx.reportError(e.getMessage());
            return;
        }
        try {
            ctx.loadTokens(resolvedPath);
            ctx.addDependency(new SourceDependencyInfo(path, resolvedPath));
            ctx.scanNestedSourceFile(resolvedPath);
        } catch (IOException e) {
            ctx.reportError("Source file not found: " + path);
        }
    }
}
