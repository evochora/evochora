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
    // An EXPORT prefix is accepted so that the line is read as an import; whether the import is
    // passed on is taken from the parser's node, not from here.
    // The same syntax is described a second time by the parser handler for this directive;
    // whatever changes in the clause pattern has to change there as well.
    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "(?i)^(?:EXPORT\\s+)?\\.IMPORT\\s+\"([^\"]+)\"(.*)$");
    private static final Pattern CLAUSES_PATTERN = Pattern.compile(
            "(?i)^\\s+AS\\s+(\\w+)((?:\\s+USING\\s+\\w+\\s+AS\\s+\\w+)*)\\s*$");
    // The alias as the preprocessor reads it, which places the module even where the rest of the
    // clauses is malformed.
    private static final Pattern ALIAS_PATTERN = Pattern.compile("(?i)^\\s+AS\\s+(\\w+)");
    private static final Pattern USING_PATTERN = Pattern.compile(
            "(?i)USING\\s+(\\w+)\\s+AS\\s+(\\w+)");

    @Override
    public Pattern pattern() {
        return IMPORT_PATTERN;
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

        // The placement's chain is formed as the preprocessor forms it: the importing placement's
        // chain followed by the alias.
        Matcher alias = ALIAS_PATTERN.matcher(matcher.group(2));
        String aliasChain = alias.find() ? childChain(ctx.placementChain(), alias.group(1)) : ctx.placementChain();

        // A directive without well-formed clauses names no dependency; its tokens are still
        // needed so the phase that reads the clauses can report what is wrong with them.
        Matcher clauses = CLAUSES_PATTERN.matcher(matcher.group(2));
        if (clauses.matches()) {
            List<ImportDependencyInfo.UsingDecl> usings = new ArrayList<>();
            Matcher usingMatcher = USING_PATTERN.matcher(clauses.group(2));
            while (usingMatcher.find()) {
                usings.add(new ImportDependencyInfo.UsingDecl(usingMatcher.group(1), usingMatcher.group(2)));
            }
            ctx.addDependency(new ImportDependencyInfo(path, clauses.group(1), usings, resolvedPath, aliasChain));
        }

        try {
            String content = ctx.loadContent(resolvedPath);
            ctx.scanNestedModule(resolvedPath, content, aliasChain);
        } catch (IOException e) {
            ctx.reportError("Module file not found: " + path);
        }
    }

    private static String childChain(String parentChain, String alias) {
        String aliasUpper = alias.toUpperCase();
        return (parentChain == null || parentChain.isEmpty()) ? aliasUpper : parentChain + "." + aliasUpper;
    }
}
