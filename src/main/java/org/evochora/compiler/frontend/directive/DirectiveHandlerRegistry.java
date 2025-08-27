package org.evochora.compiler.frontend.directive;

import org.evochora.compiler.frontend.parser.features.def.DefineDirectiveHandler;
import org.evochora.compiler.frontend.parser.features.dir.DirDirectiveHandler;
import org.evochora.compiler.frontend.preprocessor.features.include.IncludeDirectiveHandler;
import org.evochora.compiler.frontend.preprocessor.features.macro.MacroDirectiveHandler;
import org.evochora.compiler.frontend.parser.features.org.OrgDirectiveHandler;
import org.evochora.compiler.frontend.parser.features.place.PlaceDirectiveHandler;
import org.evochora.compiler.frontend.parser.features.proc.PregDirectiveHandler;
import org.evochora.compiler.frontend.parser.features.proc.ProcDirectiveHandler;
import org.evochora.compiler.frontend.parser.features.reg.RegDirectiveHandler;
import org.evochora.compiler.frontend.parser.features.require.RequireDirectiveHandler;
import org.evochora.compiler.frontend.parser.features.scope.ScopeDirectiveHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A registry for directive handlers. This class holds a map of directive names
 * to their corresponding handlers.
 */
public class DirectiveHandlerRegistry {
    private final Map<String, IDirectiveHandler> handlers = new HashMap<>();

    /**
     * Registers a new directive handler.
     * @param directiveName The name of the directive (e.g., ".DEFINE").
     * @param handler The handler for the directive.
     */
    public void register(String directiveName, IDirectiveHandler handler) {
        handlers.put(directiveName.toUpperCase(), handler);
    }

    /**
     * Gets the handler for a given directive name.
     * @param directiveName The name of the directive.
     * @return An {@link Optional} containing the handler if it exists, otherwise empty.
     */
    public Optional<IDirectiveHandler> get(String directiveName) {
        return Optional.ofNullable(handlers.get(directiveName.toUpperCase()));
    }

    /**
     * Initializes the directive handler registry with all the built-in handlers.
     * @return A new instance of {@link DirectiveHandlerRegistry} with all handlers registered.
     */
    public static DirectiveHandlerRegistry initialize() {
        DirectiveHandlerRegistry registry = new DirectiveHandlerRegistry();
        registry.register(".DEFINE", new DefineDirectiveHandler());
        registry.register(".REG", new RegDirectiveHandler());
        registry.register(".PROC", new ProcDirectiveHandler());
        registry.register(".SCOPE", new ScopeDirectiveHandler());
        // Note: Legacy .EXPORT inside a procedure is not supported anymore; use `.PROC <Name> EXPORT`.
        registry.register(".REQUIRE", new RequireDirectiveHandler());
        registry.register(".PREG", new PregDirectiveHandler());
        registry.register(".ORG", new OrgDirectiveHandler());
        registry.register(".DIR", new DirDirectiveHandler());
        registry.register(".PLACE", new PlaceDirectiveHandler());

        // Preprocessor handlers
        IncludeDirectiveHandler includeHandler = new IncludeDirectiveHandler();
        registry.register(".INCLUDE", includeHandler);
        registry.register(".MACRO", new MacroDirectiveHandler());

        return registry;
    }
}