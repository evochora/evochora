package org.evochora.compiler.backend.link;

import org.evochora.compiler.model.ir.IrDirective;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry for linking directive handlers, keyed by namespace:name.
 * Parallel to {@link org.evochora.compiler.backend.layout.LayoutDirectiveRegistry}.
 */
public final class LinkingDirectiveRegistry {

	private final Map<String, ILinkingDirectiveHandler> handlers = new HashMap<>();
	private final ILinkingDirectiveHandler defaultHandler;

	/**
	 * Constructs a new linking directive registry.
	 * @param defaultHandler The default handler to use when no specific handler is registered.
	 */
	public LinkingDirectiveRegistry(ILinkingDirectiveHandler defaultHandler) {
		this.defaultHandler = defaultHandler;
	}

	/**
	 * Registers a handler for the given namespace and name.
	 * @param namespace The namespace of the directive.
	 * @param name The name of the directive.
	 * @param handler The handler for the directive.
	 */
	public void register(String namespace, String name, ILinkingDirectiveHandler handler) {
		handlers.put((namespace + ":" + name).toLowerCase(), handler);
	}

	/**
	 * Registers all handlers from a pre-keyed map where keys are in "namespace:name" format.
	 * Bridges the map format produced by {@code FeatureRegistry.linkingDirectiveHandlers()}.
	 *
	 * @param handlers Map of "namespace:name" keys to their handlers.
	 */
	public void registerAll(Map<String, ILinkingDirectiveHandler> handlers) {
		this.handlers.putAll(handlers);
	}

	/**
	 * Gets the handler for the given directive, if one is registered.
	 * @param dir The directive to get the handler for.
	 * @return An optional containing the handler, or empty if not found.
	 */
	public Optional<ILinkingDirectiveHandler> get(IrDirective dir) {
		return Optional.ofNullable(handlers.get((dir.namespace() + ":" + dir.name()).toLowerCase()));
	}

	/**
	 * Resolves the handler for the given directive, falling back to the default handler.
	 * @param dir The directive to resolve the handler for.
	 * @return The resolved handler.
	 */
	public ILinkingDirectiveHandler resolve(IrDirective dir) {
		return get(dir).orElse(defaultHandler);
	}
}
