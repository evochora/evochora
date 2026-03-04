package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.frontend.preprocessor.features.importdir.ImportSourceHandler;
import org.evochora.compiler.frontend.preprocessor.features.macro.MacroDirectiveHandler;
import org.evochora.compiler.frontend.preprocessor.features.repeat.CaretDirectiveHandler;
import org.evochora.compiler.frontend.preprocessor.features.repeat.RepeatDirectiveHandler;
import org.evochora.compiler.frontend.preprocessor.features.source.SourceDirectiveHandler;

import org.evochora.compiler.model.token.Token;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry for preprocessing handlers. Maps token text (directive names like ".SOURCE"
 * or macro names like "EMIT") to their handlers. Unlike other compiler registries, this
 * registry is mutated at processing time — macro definitions dynamically register
 * expansion handlers.
 */
public class PreProcessorHandlerRegistry {

    private final Map<String, IPreProcessorHandler> handlers = new HashMap<>();

    /**
     * Registers a handler for a token name.
     * @param name    The token text that triggers this handler (e.g., ".SOURCE", "MY_MACRO").
     * @param handler The handler for this token.
     */
    public void register(String name, IPreProcessorHandler handler) {
        handlers.put(name.toUpperCase(), handler);
    }

    /**
     * Looks up the handler for a token name.
     * @param name The token text.
     * @return The handler, or empty if no handler is registered for this name.
     */
    public Optional<IPreProcessorHandler> get(String name) {
        return Optional.ofNullable(handlers.get(name.toUpperCase()));
    }

    /**
     * Creates a registry with all built-in preprocessing handlers.
     * @param moduleTokens Pre-lexed tokens per module (absolute path → token list), or null
     *                     for single-file compilations without imports. When provided,
     *                     registers the {@code .IMPORT} handler.
     * @return A new registry instance.
     */
    public static PreProcessorHandlerRegistry initialize(Map<String, List<Token>> moduleTokens) {
        PreProcessorHandlerRegistry registry = new PreProcessorHandlerRegistry();
        registry.register(".SOURCE", new SourceDirectiveHandler());
        registry.register(".MACRO", new MacroDirectiveHandler());
        registry.register(".REPEAT", new RepeatDirectiveHandler());
        registry.register("^", new CaretDirectiveHandler());
        registry.register(".POP_CTX", new PopCtxDirectiveHandler());
        if (moduleTokens != null) {
            registry.register(".IMPORT", new ImportSourceHandler(moduleTokens));
        }
        return registry;
    }
}
