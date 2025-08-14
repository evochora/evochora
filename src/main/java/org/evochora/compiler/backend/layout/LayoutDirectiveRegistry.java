package org.evochora.compiler.backend.layout;

import org.evochora.compiler.backend.layout.features.DirLayoutHandler;
import org.evochora.compiler.backend.layout.features.OrgLayoutHandler;
import org.evochora.compiler.backend.layout.features.PlaceLayoutHandler;
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

	public LayoutDirectiveRegistry(ILayoutDirectiveHandler defaultHandler) {
		this.defaultHandler = defaultHandler;
	}

	public void register(String namespace, String name, ILayoutDirectiveHandler handler) {
		handlers.put((namespace + ":" + name).toLowerCase(), handler);
	}

	public Optional<ILayoutDirectiveHandler> get(IrDirective dir) {
		return Optional.ofNullable(handlers.get((dir.namespace() + ":" + dir.name()).toLowerCase()));
	}

	public ILayoutDirectiveHandler resolve(IrDirective dir) {
		return get(dir).orElse(defaultHandler);
	}

	public static LayoutDirectiveRegistry initializeWithDefaults() {
		LayoutDirectiveRegistry reg = new LayoutDirectiveRegistry((directive, context) -> {
			// default: ignore unknown directives in layout
		});
		// Built-in handlers registered here
        reg.register("core", "org", new OrgLayoutHandler());
        reg.register("core", "dir", new DirLayoutHandler());
        reg.register("core", "place", new PlaceLayoutHandler());
		return reg;
	}
}


