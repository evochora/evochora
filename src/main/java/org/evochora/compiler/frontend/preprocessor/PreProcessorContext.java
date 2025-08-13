package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.frontend.preprocessor.features.macro.MacroDefinition;
import org.evochora.compiler.frontend.preprocessor.features.routine.RoutineDefinition;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Ein geteilter Kontext für die Präprozessor-Phase.
 * Enthält alle globalen Zustände, die von Direktiven-Handlern während des
 * Präprozessierens modifiziert oder gelesen werden (z.B. Makro- und Routinen-Tabellen).
 */
public class PreProcessorContext {
    private final Map<String, MacroDefinition> macroTable = new HashMap<>();
    private final Map<String, RoutineDefinition> routineTable = new HashMap<>();

    public void registerMacro(MacroDefinition macro) {
        macroTable.put(macro.name().text().toUpperCase(), macro);
    }

    public void registerRoutine(RoutineDefinition routine) {
        routineTable.put(routine.name().text().toUpperCase(), routine);
    }

    public Optional<MacroDefinition> getMacro(String name) {
        return Optional.ofNullable(macroTable.get(name.toUpperCase()));
    }

    public Optional<RoutineDefinition> getRoutine(String name) {
        return Optional.ofNullable(routineTable.get(name.toUpperCase()));
    }
}