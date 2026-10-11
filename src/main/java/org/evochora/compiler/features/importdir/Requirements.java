package org.evochora.compiler.features.importdir;

import org.evochora.compiler.model.ast.OperandNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What a placement declared with {@code .REQUIRE}, and the values its import bound, kept in the
 * placement's {@link org.evochora.compiler.model.symbols.ModuleScope#record module scope}.
 * <p>
 * The record is immutable: a declaration or a binding yields a new record, which pass 1 puts in
 * the scope's store through {@link org.evochora.compiler.model.symbols.ModuleScope#update}.
 * {@link RequireSymbolCollector} declares each requirement as it reaches the directive, and
 * {@link ImportSymbolCollector} binds the values of the import that creates the placement; the
 * record is complete after pass 1. In pass 2, {@link ImportAnalysisHandler} checks an import
 * against the requirements of the placement it creates, and {@link RequireAnalysisHandler} takes
 * the bound value of a value requirement.
 *
 * @param moduleRequirements The paths of the required modules by alias, in the order of declaration.
 * @param valueRequirements  Whether each required value carries a default, by name, in the order of
 *                           declaration.
 * @param boundValues        The values the import bound, by name, as the importer wrote them.
 */
public record Requirements(Map<String, String> moduleRequirements, Map<String, Boolean> valueRequirements,
                           Map<String, OperandNode> boundValues) {

    private static final Requirements NONE = new Requirements(Map.of(), Map.of(), Map.of());

    /**
     * Keeps the maps in their order and makes them unmodifiable.
     */
    public Requirements {
        moduleRequirements = Collections.unmodifiableMap(new LinkedHashMap<>(moduleRequirements));
        valueRequirements = Collections.unmodifiableMap(new LinkedHashMap<>(valueRequirements));
        boundValues = Collections.unmodifiableMap(new LinkedHashMap<>(boundValues));
    }

    /**
     * Returns the record of a placement that declared nothing and was bound nothing.
     *
     * @return The empty record.
     */
    public static Requirements none() {
        return NONE;
    }

    /**
     * Declares that the placement requires a module under an alias. The first declaration of an
     * alias stays.
     *
     * @param alias The alias the required module is known by in the placement, upper-cased.
     * @param path  The path the requirement names.
     * @return The record with the declaration; this record if the alias was declared already.
     */
    public Requirements withModule(String alias, String path) {
        return moduleRequirements.containsKey(alias) ? this
                : new Requirements(plus(moduleRequirements, alias, path), valueRequirements, boundValues);
    }

    /**
     * Declares that the placement requires a value under a name. The first declaration of a
     * name stays.
     *
     * @param name       The name of the value, upper-cased.
     * @param hasDefault Whether the requirement carries a default.
     * @return The record with the declaration; this record if the name was declared already.
     */
    public Requirements withValue(String name, boolean hasDefault) {
        return valueRequirements.containsKey(name) ? this
                : new Requirements(moduleRequirements, plus(valueRequirements, name, hasDefault), boundValues);
    }

    /**
     * Binds a value to a name, as the import that creates the placement supplies it. The first
     * binding of a name stays.
     *
     * @param name  The name of the value, upper-cased.
     * @param value The value as the importer wrote it.
     * @return The record with the binding; this record if the name was bound already.
     */
    public Requirements withBoundValue(String name, OperandNode value) {
        return boundValues.containsKey(name) ? this
                : new Requirements(moduleRequirements, valueRequirements, plus(boundValues, name, value));
    }

    /**
     * Returns the value the import bound to a name.
     *
     * @param name The name of the value, upper-cased.
     * @return The bound value, or empty if the import bound none.
     */
    public Optional<OperandNode> boundValue(String name) {
        return Optional.ofNullable(boundValues.get(name));
    }

    private static <V> Map<String, V> plus(Map<String, V> map, String key, V value) {
        Map<String, V> copy = new LinkedHashMap<>(map);
        copy.put(key, value);
        return copy;
    }
}
