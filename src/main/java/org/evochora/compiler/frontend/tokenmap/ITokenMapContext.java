package org.evochora.compiler.frontend.tokenmap;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.TokenKind;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;

/**
 * Context provided to {@link ITokenMapContributor} implementations during Phase 5 (Token Map Generation).
 *
 * <p>Exposes the minimum API that contributors need: adding token entries, reading the current
 * scope name, and reporting diagnostics. The {@link TokenMapGenerator} implements this interface
 * and passes itself to contributors.</p>
 */
public interface ITokenMapContext {

	/**
	 * Adds a token entry to the token map.
	 *
	 * @param sourceInfo The source location of the token.
	 * @param text       The token text.
	 * @param type       The semantic classification of the token.
	 * @param scope      The scope name to associate with the token.
	 */
	void addToken(SourceInfo sourceInfo, String text, TokenKind type, String scope);

	/**
	 * Adds a token entry to the token map with a module-qualified name for artifact lookups.
	 *
	 * @param sourceInfo    The source location of the token.
	 * @param text          The token text as it appears in source.
	 * @param type          The semantic classification of the token.
	 * @param scope         The scope name to associate with the token.
	 * @param qualifiedName The canonical module-qualified name (e.g., "ENERGY.HARVEST"), or null.
	 */
	default void addToken(SourceInfo sourceInfo, String text, TokenKind type, String scope, String qualifiedName) {
		addToken(sourceInfo, text, type, scope);
	}

	/**
	 * Returns the current module-qualified scope name (e.g., "MAIN.INIT", "global").
	 *
	 * @return The current scope name for display and annotations.
	 */
	String currentScope();

	/**
	 * Returns the diagnostics engine for reporting errors.
	 *
	 * @return The diagnostics engine.
	 */
	DiagnosticsEngine getDiagnostics();
}
