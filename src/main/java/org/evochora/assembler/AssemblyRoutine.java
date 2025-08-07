// src/main/java/org/evochora/assembler/AssemblyRoutine.java
package org.evochora.assembler;

import org.evochora.Config;
import org.evochora.organism.Instruction;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;

/**
 * Basisklasse für wiederverwendbare Assembler-Code-Templates.
 * Routinen stellen einen Code-Template mit Platzhaltern bereit, die vom
 * Hauptprogramm mit konkreten Werten (z.B. Registernamen) ersetzt werden.
 */
public abstract class AssemblyRoutine {

    /**
     * Gibt einen Code-Template mit Platzhaltern für Register zurück.
     * Platzhalter sollten in der Form `_REG_NAME_` notiert werden, z.B. `_REG_PARAM1_`.
     *
     * @return Den Assembler-Code-Template als String.
     */
    public abstract String getAssemblyCodeTemplate();


    /**
     * Kombiniert den Assembler-Code der Routine mit einem optionalen Präfix und
     * einer .ORG-Direktive.
     *
     * @param name Das optionale Präfix. Wenn null, wird kein Präfix verwendet.
     * @param relativePosition Die relative Startkoordinate.
     * @param registerMap Eine Map, die Platzhalter auf konkrete Registernamen abbildet.
     * @return Den vollständig formatierten Assembler-Code.
     */
    public String getFormattedCode(String name, int[] relativePosition, Map<String, String> registerMap) {
        StringBuilder finalCode = new StringBuilder();

        String coords = Arrays.stream(relativePosition).mapToObj(String::valueOf).collect(Collectors.joining("|"));
        finalCode.append(String.format("\n# --- Eingebundene Routine (%s) an %s ---\n", name != null ? name : "kein Präfix", coords));
        finalCode.append(String.format(".ORG %s\n", coords));

        String routineCode = this.getAssemblyCodeTemplate();

        // Schritt 1: Platzhalter-Ersetzung
        String processedCode = replacePlaceholders(routineCode, registerMap);

        // Schritt 2: Optionales Präfixing der Labels
        if (name != null) {
            processedCode = prefixRoutineLabels(processedCode, name);
        }
        finalCode.append(processedCode).append("\n");

        return finalCode.toString();
    }

    private String replacePlaceholders(String code, Map<String, String> replacements) {
        String result = code;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            // Der Platzhalter hat die Form "_KEY_"
            result = result.replaceAll(Pattern.quote("_" + entry.getKey().toUpperCase() + "_"), entry.getValue());
        }
        return result;
    }

    private String prefixRoutineLabels(String code, String prefix) {
        String upperPrefix = prefix.toUpperCase();

        // Finde alle Label-Definitionen in der Routine
        Set<String> labels = new HashSet<>();
        Pattern labelDefPattern = Pattern.compile("^\\s*([a-zA-Z0-9_]+):", Pattern.MULTILINE);
        Matcher matcher = labelDefPattern.matcher(code);
        while (matcher.find()) {
            labels.add(matcher.group(1));
        }

        // Präfixe die Label-Definitionen
        String prefixedCode = code.replaceAll("([a-zA-Z0-9_]+):", upperPrefix + "_$1:");

        // Präfixe die Label-Aufrufe
        for (String label : labels) {
            Pattern labelCallPattern = Pattern.compile("\\b" + Pattern.quote(label) + "\\b");
            prefixedCode = labelCallPattern.matcher(prefixedCode).replaceAll(upperPrefix + "_" + label);
        }

        return prefixedCode;
    }
}