package org.evochora.compiler.features.importdir;

import org.evochora.compiler.frontend.module.IDependencyScanContext;
import org.evochora.compiler.frontend.module.IDependencyScanHandler;
import org.evochora.compiler.util.SourceRootResolver;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 0 scan handler for .IMPORT directives. Detects import declarations, reads the path and
 * the alias, and triggers recursive module scanning.
 */
public class ImportDependencyScanHandler implements IDependencyScanHandler {

    // Every line that names a module file is matched, whatever follows the path, so the file is
    // loaded even when the directive is malformed and the parser can report the malformation.
    // An EXPORT prefix is accepted so that the line is read as an import; whether the import is
    // passed on is taken from the parser's node, not from here.
    // The scan knows the address of a file, path and alias, and nothing after it: the clauses
    // that follow the alias are the parser's to read.
    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "(?i)^(?:EXPORT\\s+)?\\.IMPORT\\s+\"([^\"]+)\"(.*)$");
    // The alias as the preprocessor reads it; the rest of the line is not read.
    private static final Pattern ALIAS_PATTERN = Pattern.compile("(?i)^\\s+AS\\s+(\\w+)");

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
        // chain followed by the alias. A directive without an alias names no dependency; its
        // tokens are still needed so the phase that reads the directive can report what is wrong
        // with it.
        Matcher alias = ALIAS_PATTERN.matcher(matcher.group(2));
        String aliasChain = ctx.placementChain();
        if (alias.find()) {
            aliasChain = childChain(ctx.placementChain(), alias.group(1));
            ctx.addDependency(new ImportDependencyInfo(path, alias.group(1), resolvedPath, aliasChain));
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
