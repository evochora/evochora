package org.evochora.compiler.features.source;

import org.evochora.compiler.frontend.module.IDependencyScanContext;
import org.evochora.compiler.frontend.module.IDependencyScanHandler;
import org.evochora.compiler.util.SourceRootResolver;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 0 scan handler for .SOURCE directives. Detects source file inclusions,
 * loads content for Phase 1 pre-lexing, and triggers recursive scanning for
 * nested .SOURCE directives.
 */
public class SourceDependencyScanHandler implements IDependencyScanHandler {

    private static final Pattern SOURCE_PATTERN = Pattern.compile(
            "(?i)^\\.SOURCE\\s+\"([^\"]+)\"\\s*$");

    @Override
    public Pattern pattern() {
        return SOURCE_PATTERN;
    }

    @Override
    public void handleMatch(Matcher matcher, IDependencyScanContext ctx) {
        String path = matcher.group(1);

        String resolvedPath;
        try {
            resolvedPath = ctx.resolve(path);
        } catch (SourceRootResolver.UnknownPrefixException e) {
            ctx.reportError(e.getMessage());
            return;
        }

        try {
            String content = ctx.loadContent(resolvedPath);
            ctx.registerSourceContent(resolvedPath, content);
            ctx.addDependency(new SourceDependencyInfo(path, resolvedPath));
            ctx.scanNestedSourceFile(resolvedPath, content);
        } catch (IOException e) {
            ctx.reportError("Could not load sourced file: " + path);
        }
    }
}
