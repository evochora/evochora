package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.frontend.preprocessor.features.importdir.ImportSourceHandler;
import org.evochora.compiler.frontend.preprocessor.features.macro.MacroDirectiveHandler;
import org.evochora.compiler.frontend.preprocessor.features.repeat.CaretDirectiveHandler;
import org.evochora.compiler.frontend.preprocessor.features.repeat.RepeatDirectiveHandler;
import org.evochora.compiler.frontend.preprocessor.features.source.SourceDirectiveHandler;

import org.evochora.compiler.model.Token;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry for preprocessing directive handlers.
 * Maps directive names (e.g., ".SOURCE", ".MACRO") to their handlers.
 */
public class PreProcessorDirectiveRegistry {

    private final Map<String, IPreProcessorDirectiveHandler> handlers = new HashMap<>();

    /**
     * Registers a handler for a directive name.
     * @param directiveName The directive name (e.g., ".SOURCE").
     * @param handler       The handler for this directive.
     */
    public void register(String directiveName, IPreProcessorDirectiveHandler handler) {
        handlers.put(directiveName.toUpperCase(), handler);
    }

    /**
     * Looks up the handler for a directive name.
     * @param directiveName The directive name.
     * @return The handler, or empty if no handler is registered for this directive.
     */
    public Optional<IPreProcessorDirectiveHandler> get(String directiveName) {
        return Optional.ofNullable(handlers.get(directiveName.toUpperCase()));
    }

    /**
     * Creates a registry with all built-in preprocessing handlers.
     * @param moduleTokens Pre-lexed tokens per module (absolute path → token list), or null
     *                     for single-file compilations without imports. When provided,
     *                     registers the {@code .IMPORT} handler.
     * @return A new registry instance.
     */
    public static PreProcessorDirectiveRegistry initialize(Map<String, List<Token>> moduleTokens) {
        PreProcessorDirectiveRegistry registry = new PreProcessorDirectiveRegistry();
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
