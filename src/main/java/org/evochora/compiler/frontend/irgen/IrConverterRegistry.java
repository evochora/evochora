package org.evochora.compiler.frontend.irgen;

import org.evochora.compiler.model.ast.AstNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry mapping AST node classes to converter instances, similar in spirit to DirectiveHandlerRegistry.
 * <p>
 * Provides explicit registration and a default converter fallback. The {@link #resolve(AstNode)} method
 * walks the class hierarchy to find the nearest registered converter.
 */
public final class IrConverterRegistry {

	private final Map<Class<? extends AstNode>, IAstNodeToIrConverter<? extends AstNode>> byClass = new HashMap<>();
	private final IAstNodeToIrConverter<AstNode> defaultConverter;

	private IrConverterRegistry(IAstNodeToIrConverter<AstNode> defaultConverter) {
		this.defaultConverter = defaultConverter;
	}

	/**
	 * Registers a converter for the given AST node class.
	 *
	 * @param nodeType  The concrete AST node class.
	 * @param converter The converter instance handling that class.
	 * @param <T>       Concrete AST type parameter.
	 */
	public <T extends AstNode> void register(Class<T> nodeType, IAstNodeToIrConverter<T> converter) {
		byClass.put(nodeType, converter);
	}

	/**
	 * Registers all converters from the given map. This is a bulk-registration method
	 * that bridges the wildcard-typed maps produced by {@code FeatureRegistry} with the
	 * type-safe single-entry {@link #register} method.
	 *
	 * @param converters Map of AST node classes to their converters.
	 */
	@SuppressWarnings("unchecked")
	public void registerAll(Map<Class<? extends AstNode>, IAstNodeToIrConverter<?>> converters) {
		converters.forEach((nodeType, converter) ->
				byClass.put(nodeType, (IAstNodeToIrConverter<? extends AstNode>) converter));
	}

	/**
	 * Retrieves the converter strictly registered for the given class (no hierarchy search).
	 *
	 * @param nodeType The AST node class to look up.
	 * @return Optional converter if present.
	 */
	public Optional<IAstNodeToIrConverter<? extends AstNode>> get(Class<? extends AstNode> nodeType) {
		return Optional.ofNullable(byClass.get(nodeType));
	}

	/**
	 * Resolves a converter for the given node by searching the node's concrete class,
	 * then walking up its superclasses and interfaces. Falls back to the default converter.
	 *
	 * @param node The AST node instance to resolve a converter for.
	 * @return A non-null converter to handle the node.
	 */
	@SuppressWarnings("unchecked")
	public IAstNodeToIrConverter<AstNode> resolve(AstNode node) {
		Class<?> c = node.getClass();
		while (c != null && AstNode.class.isAssignableFrom(c)) {
			IAstNodeToIrConverter<?> found = byClass.get(c);
			if (found != null) return (IAstNodeToIrConverter<AstNode>) found;
			for (Class<?> i : c.getInterfaces()) {
				if (AstNode.class.isAssignableFrom(i)) {
					found = byClass.get(i.asSubclass(AstNode.class));
					if (found != null) return (IAstNodeToIrConverter<AstNode>) found;
				}
			}
			c = c.getSuperclass();
		}
		return defaultConverter;
	}

	/**
	 * @return The default/fallback converter used when no specific converter is registered.
	 */
	public IAstNodeToIrConverter<AstNode> defaultConverter() {
		return defaultConverter;
	}

	/**
	 * Creates and initializes a registry instance with the given default converter.
	 * Specialized converters can be registered by callers after construction, mirroring
	 * the pattern of DirectiveHandlerRegistry.initialize().
	 *
	 * @param defaultConverter The fallback converter used for unknown node types.
	 * @return A new registry instance.
	 */
	public static IrConverterRegistry initialize(IAstNodeToIrConverter<AstNode> defaultConverter) {
		return new IrConverterRegistry(defaultConverter);
	}

}


