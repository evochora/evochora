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
import org.evochora.compiler.frontend.preprocessor.features.routine.RoutineDirectiveHandler;
import org.evochora.compiler.frontend.parser.features.scope.ScopeDirectiveHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class DirectiveHandlerRegistry {
    private final Map<String, IDirectiveHandler> handlers = new HashMap<>();

    public void register(String directiveName, IDirectiveHandler handler) {
        handlers.put(directiveName.toUpperCase(), handler);
    }

    public Optional<IDirectiveHandler> get(String directiveName) {
        return Optional.ofNullable(handlers.get(directiveName.toUpperCase()));
    }

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

        // Präprozessor-Handler
        IncludeDirectiveHandler includeHandler = new IncludeDirectiveHandler();
        registry.register(".INCLUDE", includeHandler);
        // TODO: Add .FILE and .INCLUDE_STRICT and differentiate in handler
        registry.register(".MACRO", new MacroDirectiveHandler());
        registry.register(".ROUTINE", new RoutineDirectiveHandler()); // NEU

        return registry;
    }
}