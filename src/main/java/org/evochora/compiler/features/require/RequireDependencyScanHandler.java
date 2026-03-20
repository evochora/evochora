package org.evochora.compiler.features.require;

import org.evochora.compiler.frontend.module.IDependencyScanContext;
import org.evochora.compiler.frontend.module.IDependencyScanHandler;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 0 scan handler for {@code .REQUIRE} directives. Detects require declarations
 * and registers them as dependencies for topological module ordering.
 */
public class RequireDependencyScanHandler implements IDependencyScanHandler {

    private static final Pattern REQUIRE_PATTERN = Pattern.compile(
            "(?i)^\\.REQUIRE\\s+\"([^\"]+)\"\\s+AS\\s+(\\w+)\\s*$");

    @Override
    public Pattern pattern() {
        return REQUIRE_PATTERN;
    }

    @Override
    public void handleMatch(Matcher matcher, IDependencyScanContext ctx) {
        String path = matcher.group(1);
        String alias = matcher.group(2);
        ctx.addDependency(new RequireDependencyInfo(path, alias));
    }
}
