package org.evochora.compiler.features.importdir;

import org.evochora.compiler.frontend.module.IDependencyScanContext;
import org.evochora.compiler.frontend.module.IDependencyScanHandler;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 0 scan handler for {@code .REQUIRE} directives. A requirement loads no file and the
 * parser alone reads the line, so the scan records only that the line is a {@code .REQUIRE}:
 * the scanner rejects it inside a {@code .SOURCE} file, which may hold no module directive.
 */
public class RequireDependencyScanHandler implements IDependencyScanHandler {

    private static final Pattern REQUIRE_PATTERN = Pattern.compile("(?i)^\\.REQUIRE\\b.*$");

    @Override
    public Pattern pattern() {
        return REQUIRE_PATTERN;
    }

    @Override
    public void handleMatch(Matcher matcher, IDependencyScanContext ctx) {
        ctx.addDependency(new RequireDependencyInfo());
    }
}
