package org.evochora.compiler.features.importdir;

import org.evochora.compiler.model.ast.OperandNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What a placement declared with {@code .REQUIRE}, and the values its import bound, kept in the
 * placement's {@link org.evochora.compiler.model.symbols.ModuleScope#state module scope}.
 * <p>
 * Filled by pass 1, read in pass 2: {@link RequireSymbolCollector} declares each requirement as
 * it reaches the directive, and {@link ImportSymbolCollector} binds the values of the import
 * that creates the placement. {@link ImportAnalysisHandler} checks an import against the
 * requirements of the placement it creates, and {@link RequireAnalysisHandler} takes the bound
 * value of a value requirement. The record is complete after pass 1; this is a rule the feature
 * keeps, which the core cannot enforce.
 */
public final class Requirements {

    private final Map<String, String> modules = new LinkedHashMap<>();
    private final Map<String, Boolean> values = new LinkedHashMap<>();
    private final Map<String, OperandNode> boundValues = new LinkedHashMap<>();

    /**
     * Declares that the placement requires a module under an alias. The first declaration of an
     * alias stays.
     *
     * @param alias The alias the required module is known by in the placement, upper-cased.
     * @param path  The path the requirement names.
     */
    public void declareModule(String alias, String path) {
        modules.putIfAbsent(alias, path);
    }

    /**
     * Returns the modules the placement requires.
     *
     * @return The paths by alias, in the order of declaration; unmodifiable.
     */
    public Map<String, String> moduleRequirements() {
        return Collections.unmodifiableMap(modules);
    }

    /**
     * Declares that the placement requires a value under a name. The first declaration of a
     * name stays.
     *
     * @param name       The name of the value, upper-cased.
     * @param hasDefault Whether the requirement carries a default.
     */
    public void declareValue(String name, boolean hasDefault) {
        values.putIfAbsent(name, hasDefault);
    }

    /**
     * Returns the values the placement requires.
     *
     * @return Whether each requirement carries a default, by name, in the order of declaration;
     *         unmodifiable.
     */
    public Map<String, Boolean> valueRequirements() {
        return Collections.unmodifiableMap(values);
    }

    /**
     * Binds a value to a name, as the import that creates the placement supplies it. The first
     * binding of a name stays.
     *
     * @param name  The name of the value, upper-cased.
     * @param value The value as the importer wrote it.
     */
    public void bindValue(String name, OperandNode value) {
        boundValues.putIfAbsent(name, value);
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
}
