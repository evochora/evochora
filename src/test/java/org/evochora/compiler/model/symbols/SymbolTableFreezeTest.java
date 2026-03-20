package org.evochora.compiler.model.symbols;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.ast.InstructionNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class SymbolTableFreezeTest {

    private SymbolTable symbolTable;

    @BeforeEach
    void setUp() {
        symbolTable = new SymbolTable(new DiagnosticsEngine());
        symbolTable.registerModule("MAIN", "test.evo");
        symbolTable.setCurrentModule("MAIN");
        symbolTable.define(new Symbol("TEST", new SourceInfo("test.evo", 1, 0), Symbol.Type.LABEL, null, true));
        symbolTable.freeze();
    }

    @Test
    void define_throwsAfterFreeze() {
        assertThatThrownBy(() -> symbolTable.define(
                new Symbol("NEW", new SourceInfo("test.evo", 2, 0), Symbol.Type.LABEL)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("frozen");
    }

    @Test
    void registerModule_throwsAfterFreeze() {
        assertThatThrownBy(() -> symbolTable.registerModule("MOD", "mod.evo"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enterScope_throwsAfterFreeze() {
        assertThatThrownBy(() -> symbolTable.enterScope("NEW_SCOPE"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void registerNodeScope_throwsAfterFreeze() {
        assertThatThrownBy(() -> symbolTable.registerNodeScope(
                new InstructionNode("NOP", List.of(), new SourceInfo("test.evo", 1, 0)),
                symbolTable.getCurrentScope()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void setCurrentModule_knownModule_allowedAfterFreeze() {
        symbolTable.setCurrentModule("MAIN");
    }

    @Test
    void setCurrentModule_unknownModule_throwsAfterFreeze() {
        assertThatThrownBy(() -> symbolTable.setCurrentModule("UNKNOWN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("frozen");
    }

    @Test
    void setCurrentScope_allowedAfterFreeze() {
        symbolTable.setCurrentScope(symbolTable.getRootScope());
    }

    @Test
    void leaveScope_allowedAfterFreeze() {
        symbolTable.leaveScope();
    }

    @Test
    void readOperations_allowedAfterFreeze() {
        assertThat(symbolTable.getCurrentAliasChain()).isNotNull();
        assertThat(symbolTable.getRootScope()).isNotNull();
        assertThat(symbolTable.getAllSymbols()).isNotEmpty();
    }

    @Test
    void resolve_allowedAfterFreeze() {
        symbolTable.setCurrentModule("MAIN");
        assertThat(symbolTable.resolve("TEST", "test.evo")).isPresent();
    }

    @Test
    void moduleScopeMapsAreImmutableAfterFreeze() {
        ModuleScope scope = symbolTable.getModuleScope("MAIN").orElseThrow();

        // All 5 map getters must return unmodifiable views after freeze
        assertThatThrownBy(() -> scope.symbols().put("X", new Symbol("X", new SourceInfo("t", 1, 0), Symbol.Type.LABEL)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> scope.imports().put("X", "Y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> scope.requires().put("X", "Y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> scope.usingBindings().put("X", "Y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> scope.importExported().put("X", true))
                .isInstanceOf(UnsupportedOperationException.class);

        // Read operations still work
        assertThat(scope.symbols().get("TEST")).isNotNull();
        assertThat(scope.imports().containsKey("NONEXISTENT")).isFalse();
    }

    @Test
    void moduleScopeMapsAreMutableBeforeFreeze() {
        SymbolTable unfrozen = new SymbolTable(new DiagnosticsEngine());
        unfrozen.registerModule("MOD", "mod.evo");
        ModuleScope scope = unfrozen.getModuleScope("MOD").orElseThrow();

        // Before freeze, maps are mutable
        scope.imports().put("LIB", "LIB_CHAIN");
        assertThat(scope.imports().get("LIB")).isEqualTo("LIB_CHAIN");

        scope.requires().put("DEP", "dep.evo");
        assertThat(scope.requires().get("DEP")).isEqualTo("dep.evo");
    }
}
