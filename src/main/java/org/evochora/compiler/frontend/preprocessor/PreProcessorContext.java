package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.frontend.preprocessor.features.macro.MacroDefinition;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A shared context for the preprocessor phase.
 * Contains all global states that are modified or read by directive handlers
 * during preprocessing (e.g., macro and routine tables).
 */
public class PreProcessorContext {
    private final Map<String, MacroDefinition> macroTable = new HashMap<>();

    /**
     * Registers a new macro definition.
     * @param macro The macro definition to register.
     */
    public void registerMacro(MacroDefinition macro) {
        macroTable.put(macro.name().text().toUpperCase(), macro);
    }

    /**
     * Gets a macro definition by its name.
     * @param name The name of the macro.
     * @return An {@link Optional} containing the macro definition if it exists, otherwise empty.
     */
    public Optional<MacroDefinition> getMacro(String name) {
        return Optional.ofNullable(macroTable.get(name.toUpperCase()));
    }
}