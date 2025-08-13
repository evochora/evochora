package org.evochora.compiler.core.directives;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Eine Registry zum Speichern und Abrufen von Handlern für die verschiedenen Compiler-Direktiven.
 */
    /**
     * Eine Registry zum Speichern und Abrufen von Handlern für die verschiedenen Compiler-Direktiven.
     */
    public class DirectiveHandlerRegistry {
        private final Map<String, IDirectiveHandler> handlers = new HashMap<>();

        /**
         * Registriert einen neuen Handler für einen Direktiven-Namen.
         * @param directiveName Der Name der Direktive (z.B. ".PROC").
         * @param handler Die Handler-Implementierung.
         */
        public void register(String directiveName, IDirectiveHandler handler) {
            handlers.put(directiveName.toUpperCase(), handler);
        }

        /**
         * Ruft den Handler für einen bestimmten Direktiven-Namen ab.
         * @param directiveName Der Name der Direktive.
         * @return Ein {@link Optional}, das den Handler enthält, wenn er existiert.
         */
        public Optional<IDirectiveHandler> get(String directiveName) {
            return Optional.ofNullable(handlers.get(directiveName.toUpperCase()));
        }

        /**
         * Initialisiert die Registry mit allen bekannten Direktiven-Handlern.
         * @return Eine vollständig initialisierte Registry.
         */
    /**
     * Initialisiert die Registry mit allen bekannten Direktiven-Handlern.
     * @return Eine vollständig initialisierte Registry.
     */
    public static DirectiveHandlerRegistry initialize() {
        DirectiveHandlerRegistry registry = new DirectiveHandlerRegistry();
        registry.register(".DEFINE", new DefineDirectiveHandler());
            registry.register(".REG", new RegDirectiveHandler());
            registry.register(".PROC", new ProcDirectiveHandler());
        return registry;
    }
}
