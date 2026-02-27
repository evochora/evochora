package org.evochora.compiler.frontend.parser;

import org.evochora.compiler.frontend.parser.features.ctx.PopCtxDirectiveHandler;
import org.evochora.compiler.frontend.parser.features.ctx.PushCtxDirectiveHandler;
import org.evochora.compiler.frontend.parser.features.def.DefineDirectiveHandler;
import org.evochora.compiler.frontend.parser.features.dir.DirDirectiveHandler;
import org.evochora.compiler.frontend.parser.features.importdir.ImportDirectiveHandler;
import org.evochora.compiler.frontend.parser.features.org.OrgDirectiveHandler;
import org.evochora.compiler.frontend.parser.features.place.PlaceDirectiveHandler;
import org.evochora.compiler.frontend.parser.features.proc.PregDirectiveHandler;
import org.evochora.compiler.frontend.parser.features.proc.ProcDirectiveHandler;
import org.evochora.compiler.frontend.parser.features.reg.RegDirectiveHandler;
import org.evochora.compiler.frontend.parser.features.require.RequireDirectiveHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry for parser directive handlers.
 * Maps directive names (e.g., ".ORG", ".PROC") to their handlers.
 */
public class ParserDirectiveRegistry {

    private final Map<String, IParserDirectiveHandler> handlers = new HashMap<>();

    /**
     * Registers a handler for a directive name.
     * @param directiveName The directive name (e.g., ".ORG").
     * @param handler       The handler for this directive.
     */
    public void register(String directiveName, IParserDirectiveHandler handler) {
        handlers.put(directiveName.toUpperCase(), handler);
    }

    /**
     * Looks up the handler for a directive name.
     * @param directiveName The directive name.
     * @return The handler, or empty if no handler is registered for this directive.
     */
    public Optional<IParserDirectiveHandler> get(String directiveName) {
        return Optional.ofNullable(handlers.get(directiveName.toUpperCase()));
    }

    /**
     * Creates a registry with all built-in parser directive handlers.
     * @return A new registry instance.
     */
    public static ParserDirectiveRegistry initialize() {
        ParserDirectiveRegistry registry = new ParserDirectiveRegistry();
        registry.register(".DEFINE", new DefineDirectiveHandler());
        registry.register(".REG", new RegDirectiveHandler());
        registry.register(".PROC", new ProcDirectiveHandler());
        registry.register(".PREG", new PregDirectiveHandler());
        registry.register(".ORG", new OrgDirectiveHandler());
        registry.register(".DIR", new DirDirectiveHandler());
        registry.register(".PLACE", new PlaceDirectiveHandler());
        registry.register(".IMPORT", new ImportDirectiveHandler());
        registry.register(".REQUIRE", new RequireDirectiveHandler());
        registry.register(".PUSH_CTX", new PushCtxDirectiveHandler());
        registry.register(".POP_CTX", new PopCtxDirectiveHandler());
        return registry;
    }
}
