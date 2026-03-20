package org.evochora.compiler.frontend.postprocess;

import org.evochora.compiler.model.ast.AstNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Class-keyed registry that maps AST node types to their {@link IPostProcessHandler}.
 *
 * <p>During the collect pass, {@link AstPostProcessor} consults this registry to dispatch
 * feature-specific node processing. This replaces hardcoded {@code instanceof} dispatch
 * chains with extensible, feature-driven registration.</p>
 */
public final class PostProcessHandlerRegistry {

	private final Map<Class<? extends AstNode>, IPostProcessHandler> handlers = new HashMap<>();

	/**
	 * Registers a handler for a specific AST node type.
	 *
	 * @param nodeType The AST node class this handler processes.
	 * @param handler  The handler implementation.
	 */
	public void register(Class<? extends AstNode> nodeType, IPostProcessHandler handler) {
		handlers.put(nodeType, handler);
	}

	/**
	 * Bulk-registers handlers from a feature registry map.
	 *
	 * @param handlers Map of AST node classes to their post-process handlers.
	 */
	public void registerAll(Map<Class<? extends AstNode>, IPostProcessHandler> handlers) {
		this.handlers.putAll(handlers);
	}

	/**
	 * Looks up the handler for the given AST node type.
	 *
	 * @param nodeType The AST node class to look up.
	 * @return The handler, or empty if no handler is registered for this type.
	 */
	public Optional<IPostProcessHandler> get(Class<? extends AstNode> nodeType) {
		return Optional.ofNullable(handlers.get(nodeType));
	}
}
