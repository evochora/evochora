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

    // Every line that names a module file is matched, whatever follows the path, so the file is
    // loaded even when the directive is malformed and the parser can report the malformation.
    // The same syntax is described a second time by the parser handler for this directive;
    // whatever changes in the clause pattern has to change there as well.
    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "(?i)^(EXPORT\\s+)?\\.IMPORT\\s+\"([^\"]+)\"(.*)$");
    private static final Pattern CLAUSES_PATTERN = Pattern.compile(
            "(?i)^\\s+AS\\s+(\\w+)((?:\\s+USING\\s+\\w+\\s+AS\\s+\\w+)*)\\s*$");
    private static final Pattern USING_PATTERN = Pattern.compile(
            "(?i)USING\\s+(\\w+)\\s+AS\\s+(\\w+)");

    @Override
    public Pattern pattern() {
        return IMPORT_PATTERN;
    }

    @Override
    public void handleMatch(Matcher matcher, IDependencyScanContext ctx) {
        boolean exported = matcher.group(1) != null;
        String path = matcher.group(2);

        String resolvedPath;
        try {
            resolvedPath = ctx.resolve(path);
        } catch (SourceRootResolver.UnknownPrefixException e) {
            ctx.reportError(e.getMessage());
            return;
        }

        // A directive without a well-formed alias clause names no dependency; its tokens are still
        // needed so the phase that reads the clause can report what is wrong with it.
        Matcher clauses = CLAUSES_PATTERN.matcher(matcher.group(3));
        if (clauses.matches()) {
            String alias = clauses.group(1);
            List<ImportDependencyInfo.UsingDecl> usings = new ArrayList<>();
            Matcher usingMatcher = USING_PATTERN.matcher(clauses.group(2));
            while (usingMatcher.find()) {
                usings.add(new ImportDependencyInfo.UsingDecl(usingMatcher.group(1), usingMatcher.group(2)));
            }
            ctx.addDependency(new ImportDependencyInfo(path, alias, usings, resolvedPath, exported));
        }

        try {
            String content = ctx.loadContent(resolvedPath);
            ctx.scanNestedModule(resolvedPath, content);
        } catch (IOException e) {
            ctx.reportError("Module file not found: " + path);
        }
    }
}
