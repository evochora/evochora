package org.evochora.compiler.frontend.tokenmap;

import org.evochora.compiler.model.ast.AstNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Class-keyed registry that maps AST node types to their {@link ITokenMapContributor}.
 *
 * <p>During token map generation, the {@link TokenMapGenerator} consults this registry
 * before falling back to default handling. This replaces hardcoded {@code instanceof}
 * dispatch chains with extensible, feature-driven registration.</p>
 */
public final class TokenMapContributorRegistry {

	private final Map<Class<? extends AstNode>, ITokenMapContributor> contributors = new HashMap<>();

	/**
	 * Registers a contributor for a specific AST node type.
	 *
	 * @param nodeType    The AST node class this contributor handles.
	 * @param contributor The contributor implementation.
	 */
	public void register(Class<? extends AstNode> nodeType, ITokenMapContributor contributor) {
		contributors.put(nodeType, contributor);
	}

	/**
	 * Looks up the contributor for the given AST node type.
	 *
	 * @param nodeType The AST node class to look up.
	 * @return The contributor, or empty if no contributor is registered for this type.
	 */
	public Optional<ITokenMapContributor> get(Class<? extends AstNode> nodeType) {
		return Optional.ofNullable(contributors.get(nodeType));
	}
}
