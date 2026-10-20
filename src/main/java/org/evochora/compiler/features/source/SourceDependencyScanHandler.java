package org.evochora.compiler.features.source;

import org.evochora.compiler.frontend.DirectiveLine;
import org.evochora.compiler.frontend.lexer.IDependencyScanContext;
import org.evochora.compiler.frontend.lexer.IDependencyScanHandler;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.util.SourceRootResolver;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Lexer-phase handler for {@code .SOURCE "path"}: reads the file so that its own directives are
 * followed, and records the dependency. A line that is not exactly the directive and one path
 * names no file here; the preprocessor reports what is wrong with it. A file that sources,
 * directly or through other source files, the file it is included from is a circular inclusion,
 * reported at the line that closes the circle; the handler keeps the chain of inclusions in its
 * own state, since the phase reads a source file within the call that includes it.
 */
public class SourceDependencyScanHandler implements IDependencyScanHandler {

    /** The source files on the way to the one being read, kept in the phase's state. */
    static final class Inclusions {
        final Set<String> chain = new HashSet<>();
    }

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
        Set<String> chain = ctx.getOrCreate(Inclusions.class, Inclusions::new).chain;
        if (resolvedPath.equals(ctx.sourcePath()) || chain.contains(resolvedPath)) {
            ctx.reportError("Circular .SOURCE detected: " + path);
            return;
        }
        chain.add(ctx.sourcePath());
        try {
            ctx.scanNestedSourceFile(resolvedPath);
            ctx.addDependency(new SourceDependencyInfo(path, resolvedPath));
        } catch (IOException e) {
            ctx.reportError("Source file not found: " + path);
        } finally {
            chain.remove(ctx.sourcePath());
        }
    }
}
