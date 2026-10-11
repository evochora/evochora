package org.evochora.compiler.model.symbols;

import org.evochora.compiler.api.SourceInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A module's namespace keeps its own rules: the first symbol of a name stays, an alias binds
 * once, a record is created once, and nothing changes after the freeze.
 */
@Tag("unit")
class ModuleScopeTest {

    private static Symbol symbol(String name, int line) {
        return new Symbol(name, new SourceInfo("m.evo", line, 1, "", 0), Symbol.Type.LABEL);
    }

    @Test
    void theFirstSymbolOfANameStays() {
        ModuleScope scope = new ModuleScope("M", "m.evo");
        Symbol first = symbol("X", 1);

        assertThat(scope.defineSymbol("X", first)).isEmpty();
        assertThat(scope.defineSymbol("X", symbol("X", 2))).contains(first);
        assertThat(scope.symbols()).containsEntry("X", first);
    }

    @Test
    void anAliasBindsOnceAndTheSameBindingAgainIsNoConflict() {
        ModuleScope scope = new ModuleScope("M", "m.evo");

        assertThat(scope.bindAlias("LIB", "M.LIB")).isTrue();
        assertThat(scope.bindAlias("LIB", "M.LIB")).isTrue();
        assertThat(scope.bindAlias("DEP", "M.A")).isTrue();

        assertThat(scope.bindAlias("LIB", "M.OTHER")).isFalse();
        assertThat(scope.bindAlias("DEP", "M.B")).isFalse();
        assertThat(scope.aliases()).containsEntry("LIB", "M.LIB").containsEntry("DEP", "M.A");
    }

    @Test
    void aRecordIsCreatedOncePerKey() {
        ModuleScope scope = new ModuleScope("M", "m.evo");

        StringBuilder first = scope.state(StringBuilder.class, StringBuilder::new);

        assertThat(scope.state(StringBuilder.class, () -> new StringBuilder("other"))).isSameAs(first);
    }

    @Test
    void nothingChangesAfterTheFreeze() {
        ModuleScope scope = new ModuleScope("M", "m.evo");
        scope.freeze();

        assertThatThrownBy(() -> scope.defineSymbol("X", symbol("X", 1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> scope.bindAlias("LIB", "M.LIB")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> scope.state(StringBuilder.class, StringBuilder::new)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theMapsHandedOutCannotBeWritten() {
        ModuleScope scope = new ModuleScope("M", "m.evo");

        assertThatThrownBy(() -> scope.symbols().put("X", symbol("X", 1))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> scope.aliases().put("X", "Y")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(Optional.ofNullable(scope.symbols().get("X"))).isEmpty();
    }
}
