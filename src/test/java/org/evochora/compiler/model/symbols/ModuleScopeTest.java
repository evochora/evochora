package org.evochora.compiler.model.symbols;

import org.evochora.compiler.api.SourceInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A module's namespace keeps its own rules: the first symbol of a name stays, an alias binds
 * once, and nothing changes after the freeze.
 */
@Tag("unit")
class ModuleScopeTest {

    private static Symbol symbol(String name, int line) {
        return new Symbol(name, new SourceInfo("m.evo", line, 1), Symbol.Type.LABEL);
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

        assertThat(scope.addImport("LIB", "M.LIB", true)).isTrue();
        assertThat(scope.addImport("LIB", "M.LIB", true)).isTrue();
        assertThat(scope.addRequirement("DEP", "dep.evo")).isTrue();
        assertThat(scope.bindUsing("DEP", "M.A")).isTrue();

        assertThat(scope.addImport("LIB", "M.OTHER", false)).isFalse();
        assertThat(scope.addRequirement("DEP", "other.evo")).isFalse();
        assertThat(scope.bindUsing("DEP", "M.B")).isFalse();
        assertThat(scope.imports()).containsEntry("LIB", "M.LIB");
        assertThat(scope.importExported()).containsEntry("LIB", true);
        assertThat(scope.requires()).containsEntry("DEP", "dep.evo");
        assertThat(scope.usingBindings()).containsEntry("DEP", "M.A");
    }

    @Test
    void nothingChangesAfterTheFreeze() {
        ModuleScope scope = new ModuleScope("M", "m.evo");
        scope.freeze();

        assertThatThrownBy(() -> scope.defineSymbol("X", symbol("X", 1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> scope.addImport("LIB", "M.LIB", false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> scope.addRequirement("DEP", "dep.evo")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> scope.bindUsing("DEP", "M.A")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theMapsHandedOutCannotBeWritten() {
        ModuleScope scope = new ModuleScope("M", "m.evo");

        assertThatThrownBy(() -> scope.symbols().put("X", symbol("X", 1))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> scope.imports().put("X", "Y")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> scope.requires().put("X", "Y")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> scope.usingBindings().put("X", "Y")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> scope.importExported().put("X", true)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(Optional.ofNullable(scope.symbols().get("X"))).isEmpty();
    }
}
