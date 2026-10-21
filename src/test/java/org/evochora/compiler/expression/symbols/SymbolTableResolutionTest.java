package org.evochora.compiler.expression.symbols;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.expression.NameAnswer;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IIdentifierBinding;
import org.evochora.compiler.model.ast.IJumpTarget;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.LiteralNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.model.expression.Kind;
import org.evochora.compiler.model.expression.NameExpression;
import org.evochora.compiler.model.expression.Value;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SymbolTableResolution}: what a name in a computation stands for, asked of
 * the node that defined it and followed through a chain of definitions.
 */
@Tag("unit")
class SymbolTableResolutionTest {

    private static final String MAIN = "/p/main.evo";

    private SymbolTable table;
    private SymbolTableResolution resolution;

    /** A definition that stands for whatever node it was given. */
    private record Binding(AstNode target) implements AstNode, IIdentifierBinding {
        @Override
        public AstNode bind(IdentifierNode reference) {
            return target;
        }
    }

    /** A node that marks a place in the code. */
    private record Label() implements AstNode, IJumpTarget {
    }

    /** The definition of an alias of the module level. */
    private record Alias() implements AstNode, org.evochora.compiler.model.ast.IPlacementAlias {
    }

    /** A node that is neither a binding nor a jump target. */
    private record Other() implements AstNode {
    }

    private static SourceInfo at(int line) {
        return new SourceInfo(MAIN, line, 1, "", 0);
    }

    private void define(String name, AstNode node) {
        table.define(new Symbol(name, at(1), Symbol.Type.CONSTANT, node));
    }

    private NameAnswer resolve(String name) {
        return resolution.resolve(new NameExpression(name, at(9), at(9).plusColumns(name.length())));
    }

    @BeforeEach
    void setUp() {
        table = new SymbolTable(new DiagnosticsEngine());
        table.registerModule("", MAIN);
        table.setCurrentModule("");
        resolution = new SymbolTableResolution(table);
    }

    @Test
    void aChainOfAliasesEndsAtTheLiteral() {
        define("WIDTH", new Binding(new LiteralNode(new Value.Number(100), at(1))));
        define("W", new Binding(new IdentifierNode("WIDTH", at(2))));
        define("SIZE", new Binding(new IdentifierNode("W", at(3))));

        assertThat(resolve("SIZE")).isEqualTo(new NameAnswer.Value(new Value.Number(100)));
    }

    @Test
    void aLabelIsAPositionForLater() {
        define("START", new Label());
        define("LOCAL", new Binding(new IdentifierNode("START", at(2))));

        assertThat(resolve("START")).isEqualTo(new NameAnswer.Later(Kind.POSITION, "START"));
        assertThat(resolve("LOCAL")).isEqualTo(new NameAnswer.Later(Kind.POSITION, "START"));
    }

    @Test
    void aRegisterCannotBeUsed() {
        define("COUNTER", new Binding(new RegisterNode("%DR0", at(1))));

        assertThat(resolve("COUNTER")).isEqualTo(new NameAnswer.NotUsable("Register '%DR0' cannot be used in an expression."));
    }

    @Test
    void anUndefinedNameIsNotDefined() {
        assertThat(resolve("FOO")).isEqualTo(new NameAnswer.NotUsable("'FOO' is not defined."));
        assertThat(resolve("LIB.FOO")).isInstanceOf(NameAnswer.NotUsable.class);
    }

    @Test
    void aDefinitionWithoutAValueHasBeenReported() {
        define("EMPTY", new Binding(null));

        assertThat(resolve("EMPTY")).isInstanceOf(NameAnswer.Reported.class);
    }

    @Test
    void aNameOfSomethingElseIsNoValue() {
        define("THING", new Other());

        assertThat(resolve("THING")).isEqualTo(new NameAnswer.NotUsable("'THING' cannot be used as a value."));
    }

    @Test
    void aChainThatEndsAtAModuleAliasIsNoValue() {
        define("UTIL", new Alias());
        define("U", new Binding(new IdentifierNode("UTIL", at(2))));

        assertThat(resolve("U")).isEqualTo(new NameAnswer.NotUsable("'U' cannot be used as a value."));
    }

    @Test
    void aChainEndsAtALabelOfAModule() {
        table.registerModule("LIB", "/p/lib.evo");
        table.getModuleScope("").orElseThrow().bindAlias("LIB", "LIB");
        table.setCurrentModule("LIB");
        SourceInfo inLib = new SourceInfo("/p/lib.evo", 1, 1, "LIB", 0);
        table.define(new Symbol("TARGET", inLib, Symbol.Type.LABEL, new Label()));
        table.define(new Symbol("LOCAL", inLib, Symbol.Type.CONSTANT,
                new Binding(new IdentifierNode("TARGET", new SourceInfo("/p/lib.evo", 2, 14, "LIB", 0)))));

        SourceInfo local = new SourceInfo("/p/lib.evo", 3, 1, "LIB", 0);
        assertThat(resolution.resolve(new NameExpression("LOCAL", local, local.plusColumns(5))))
                .isEqualTo(new NameAnswer.Later(Kind.POSITION, "LIB.TARGET"));
    }

    @Test
    void theCursorIsAPositionForLater() {
        assertThat(resolve("@")).isEqualTo(new NameAnswer.Later(Kind.POSITION, "@"));
    }
}
