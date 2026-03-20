package org.evochora.compiler.backend.layout;

import org.evochora.compiler.model.ir.IrDirective;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry for layout directive handlers, keyed by namespace:name.
 */
public final class LayoutDirectiveRegistry {

	private final Map<String, ILayoutDirectiveHandler> handlers = new HashMap<>();
	private final ILayoutDirectiveHandler defaultHandler;

	/**
	 * Constructs a new layout directive registry.
	 * @param defaultHandler The default handler to use when no specific handler is registered.
	 */
	public LayoutDirectiveRegistry(ILayoutDirectiveHandler defaultHandler) {
		this.defaultHandler = defaultHandler;
	}

	/**
	 * Registers a new layout directive handler.
	 * @param namespace The namespace of the directive.
	 * @param name The name of the directive.
	 * @param handler The handler for the directive.
	 */
	public void register(String namespace, String name, ILayoutDirectiveHandler handler) {
		handlers.put((namespace + ":" + name).toLowerCase(), handler);
	}

	/**
	 * Gets the handler for the given directive, if one is registered.
	 * @param dir The directive to get the handler for.
	 * @return An optional containing the handler, or empty if not found.
	 */
	public Optional<ILayoutDirectiveHandler> get(IrDirective dir) {
		return Optional.ofNullable(handlers.get((dir.namespace() + ":" + dir.name()).toLowerCase()));
	}

	/**
	 * Resolves the handler for the given directive, falling back to the default handler if none is found.
	 * @param dir The directive to resolve the handler for.
	 * @return The resolved handler.
	 */
	public ILayoutDirectiveHandler resolve(IrDirective dir) {
		return get(dir).orElse(defaultHandler);
	}

	/**
	 * Registers all handlers from a pre-keyed map where keys are in "namespace:name" format.
	 * Bridges the map format produced by {@code FeatureRegistry.layoutHandlers()} with
	 * this registry's internal storage.
	 *
	 * @param handlers Map of "namespace:name" keys to their handlers.
	 */
	public void registerAll(Map<String, ILayoutDirectiveHandler> handlers) {
		this.handlers.putAll(handlers);
	}
}


