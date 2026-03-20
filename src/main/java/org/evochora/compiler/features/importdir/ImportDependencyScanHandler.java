package org.evochora.compiler.features.importdir;

import org.evochora.compiler.frontend.module.IDependencyScanContext;
import org.evochora.compiler.frontend.module.IDependencyScanHandler;
import org.evochora.compiler.util.SourceRootResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 0 scan handler for .IMPORT directives. Detects import declarations,
 * parses USING clauses, and triggers recursive module scanning.
 */
public class ImportDependencyScanHandler implements IDependencyScanHandler {

    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "(?i)^\\.IMPORT\\s+\"([^\"]+)\"\\s+AS\\s+(\\w+)((?:\\s+USING\\s+\\w+\\s+AS\\s+\\w+)*)\\s*$");
    private static final Pattern USING_PATTERN = Pattern.compile(
            "(?i)USING\\s+(\\w+)\\s+AS\\s+(\\w+)");

    @Override
    public Pattern pattern() {
        return IMPORT_PATTERN;
    }

    @Override
    public void handleMatch(Matcher matcher, IDependencyScanContext ctx) {
        String path = matcher.group(1);
        String alias = matcher.group(2);
        String usingsPart = matcher.group(3);

        List<ImportDependencyInfo.UsingDecl> usings = new ArrayList<>();
        if (usingsPart != null && !usingsPart.isBlank()) {
            Matcher usingMatcher = USING_PATTERN.matcher(usingsPart);
            while (usingMatcher.find()) {
                usings.add(new ImportDependencyInfo.UsingDecl(usingMatcher.group(1), usingMatcher.group(2)));
            }
        }

        String resolvedPath;
        try {
            resolvedPath = ctx.resolve(path);
        } catch (SourceRootResolver.UnknownPrefixException e) {
            ctx.reportError(e.getMessage());
            return;
        }

        ctx.addDependency(new ImportDependencyInfo(path, alias, usings, resolvedPath));

        try {
            String content = ctx.loadContent(resolvedPath);
            ctx.scanNestedModule(resolvedPath, content);
        } catch (IOException e) {
            ctx.reportError("Could not load imported module: " + path);
        }
    }
}
