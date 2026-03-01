package org.evochora.compiler.frontend.module;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handler for a single dependency directive pattern in Phase 0 (Dependency Scanning).
 *
 * <p>Each handler provides a regex {@link Pattern} that matches one type of dependency
 * directive (e.g., {@code .IMPORT}, {@code .REQUIRE}, {@code .SOURCE}). When the
 * {@link DependencyScanner} finds a matching line, it calls {@link #handleMatch} with
 * the regex match result and a context for recording the dependency.</p>
 */
public interface IDependencyScanHandler {

	/**
	 * Returns the regex pattern that this handler matches against source lines.
	 *
	 * @return The compiled regex pattern for this dependency directive.
	 */
	Pattern pattern();

	/**
	 * Handles a matched dependency directive line.
	 *
	 * @param matcher The regex matcher with captured groups from the matched line.
	 * @param ctx     The scan context for recording imports, requires, and source files.
	 */
	void handleMatch(Matcher matcher, IDependencyScanContext ctx);
}
