package org.evochora.compiler.backend.layout;

import org.evochora.compiler.backend.layout.features.DirLayoutHandler;
import org.evochora.compiler.backend.layout.features.OrgLayoutHandler;
import org.evochora.compiler.backend.layout.features.PlaceLayoutHandler;
import org.evochora.compiler.backend.layout.features.PopCtxLayoutHandler;
import org.evochora.compiler.backend.layout.features.PushCtxLayoutHandler;
import org.evochora.compiler.ir.IrDirective;

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
	 * Initializes a new layout directive registry with the default handlers.
	 * @return A new registry with default handlers.
	 */
	public static LayoutDirectiveRegistry initializeWithDefaults() {
		LayoutDirectiveRegistry reg = new LayoutDirectiveRegistry((directive, context) -> {
			// default: ignore unknown directives in layout
		});
		// Built-in handlers registered here
        reg.register("core", "org", new OrgLayoutHandler());
        reg.register("core", "dir", new DirLayoutHandler());
        reg.register("core", "place", new PlaceLayoutHandler());
        reg.register("core", "push_ctx", new PushCtxLayoutHandler());
        reg.register("core", "pop_ctx", new PopCtxLayoutHandler());
		return reg;
	}
}


