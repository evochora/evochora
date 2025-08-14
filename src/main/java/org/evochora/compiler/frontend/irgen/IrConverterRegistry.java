package org.evochora.compiler.frontend.irgen;

import org.evochora.compiler.frontend.irgen.converters.DirNodeConverter;
import org.evochora.compiler.frontend.irgen.converters.InstructionNodeConverter;
import org.evochora.compiler.frontend.irgen.converters.LabelNodeConverter;
import org.evochora.compiler.frontend.irgen.converters.OrgNodeConverter;
import org.evochora.compiler.frontend.irgen.converters.PlaceNodeConverter;
import org.evochora.compiler.frontend.irgen.converters.ProcedureNodeConverter;
import org.evochora.compiler.frontend.irgen.converters.ScopeNodeConverter;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.InstructionNode;
import org.evochora.compiler.frontend.parser.features.dir.DirNode;
import org.evochora.compiler.frontend.parser.features.label.LabelNode;
import org.evochora.compiler.frontend.parser.features.org.OrgNode;
import org.evochora.compiler.frontend.parser.features.place.PlaceNode;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;
import org.evochora.compiler.frontend.parser.features.scope.ScopeNode;

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

	/**
	 * Initializes a registry with the default converter and registers all built-in converters.
	 * Mirrors the style of DirectiveHandlerRegistry.initialize().
	 *
	 * @return A registry pre-populated with the standard converters.
	 */
	public static IrConverterRegistry initializeWithDefaults() {
		IrConverterRegistry reg = initialize(new DefaultAstNodeToIrConverter());
		reg.register(InstructionNode.class, new InstructionNodeConverter());
		reg.register(LabelNode.class, new LabelNodeConverter());
		reg.register(OrgNode.class, new OrgNodeConverter());
		reg.register(DirNode.class, new DirNodeConverter());
		reg.register(PlaceNode.class, new PlaceNodeConverter());
		reg.register(ProcedureNode.class, new ProcedureNodeConverter());
		reg.register(ScopeNode.class, new ScopeNodeConverter());
		return reg;
	}
}


