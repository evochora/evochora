package org.evochora.compiler.model.symbols;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IIdentifierBinding;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.NumberLiteralNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the placement rules of {@link SymbolTable}: a name is filed under the placement
 * it is defined in, looked up in the placement of the position it is written at, and a
 * definition that leads into another placement is followed there.
 */
@Tag("unit")
class SymbolTableTest {

    private static final String MAIN = "/p/main.evo";
    private static final String LIB = "/p/lib.evo";
    private static final String CONSTS = "/p/consts.evo";

    private SymbolTable table;

    /** A definition that stands for whatever node it was given. */
    private record Binding(AstNode target) implements AstNode, IIdentifierBinding {
        @Override
        public AstNode bind(IdentifierNode reference) {
            return target;
        }
    }

    @BeforeEach
    void setUp() {
        table = new SymbolTable(new DiagnosticsEngine());
        table.registerModule("", MAIN);
        table.registerModule("LIB", LIB);
        table.getModuleScope("").orElseThrow().addImport("LIB", "LIB");
    }

    @Test
    void aNameDefinedInAPlacementIsFoundFromEveryFileOfThatPlacement() {
        table.setCurrentModule("LIB");
        table.define(new Symbol("A", new SourceInfo(CONSTS, 1, 8, "LIB", 0), Symbol.Type.CONSTANT));

        assertThat(table.resolve("A", new SourceInfo(LIB, 3, 1, "LIB", 0)).found()).isPresent();
        assertThat(table.resolve("A", new SourceInfo(CONSTS, 2, 1, "LIB", 0)).found())
                .map(ResolvedSymbol::qualifiedName).contains("LIB.A");
    }

    @Test
    void aNameIsNotFoundFromAPositionOfAnotherPlacement() {
        table.setCurrentModule("LIB");
        table.define(new Symbol("A", new SourceInfo(LIB, 1, 8, "LIB", 0), Symbol.Type.CONSTANT));

        assertThat(table.resolve("A", new SourceInfo(LIB, 1, 8, "OTHER", 0)).found()).isEmpty();
    }

    @Test
    void aSecondDefinitionInThePlacementKeepsTheFirst() {
        table.setCurrentModule("LIB");
        Symbol first = new Symbol("A", new SourceInfo(LIB, 1, 8, "LIB", 0), Symbol.Type.CONSTANT);
        table.define(first);

        Optional<Symbol> existing = table.define(new Symbol("A", new SourceInfo(CONSTS, 4, 8, "LIB", 0),
                Symbol.Type.CONSTANT));

        assertThat(existing).containsSame(first);
    }

    @Test
    void aDefinitionThatLeadsIntoAnotherPlacementIsFollowedThere() {
        SourceInfo libX = new SourceInfo(LIB, 1, 8, "LIB", 0);
        SourceInfo libY = new SourceInfo(CONSTS, 2, 8, "LIB", 0);
        table.setCurrentModule("LIB");
        table.define(new Symbol("X", libX, Symbol.Type.CONSTANT,
                new Binding(new IdentifierNode("Y", libY)), true));
        table.define(new Symbol("Y", libY, Symbol.Type.CONSTANT, new Binding(new NumberLiteralNode(7, libY))));
        table.setCurrentModule("");
        // A Y of the importing placement, which the binding must not reach
        SourceInfo mainY = new SourceInfo(MAIN, 1, 8, "", 0);
        table.define(new Symbol("Y", mainY, Symbol.Type.CONSTANT, new Binding(new NumberLiteralNode(9, mainY))));

        Optional<AstNode> bound = table.bindingOf(new IdentifierNode("LIB.X", new SourceInfo(MAIN, 3, 10, "", 0)));

        assertThat(bound).containsInstanceOf(NumberLiteralNode.class);
        assertThat(((NumberLiteralNode) bound.orElseThrow()).value()).isEqualTo(7);
    }

    /**
     * A path descends from a symbol into the scope its node opens. Of a scope the writer does
     * not stand in, only an exported name is found; the find is qualified by the scope's path and
     * reports that scope.
     */
    @Test
    void aPathDescendsIntoTheScopeASymbolOpens() {
        table.setCurrentModule("LIB");
        SourceInfo procedure = new SourceInfo(LIB, 1, 7, "LIB", 0);
        AstNode procedureNode = new IdentifierNode("P", procedure);
        table.define(new Symbol("P", procedure, Symbol.Type.PROCEDURE, procedureNode, true));
        table.registerNodeScope(procedureNode, table.enterScope("P"));
        table.define(new Symbol("SHOWN", new SourceInfo(LIB, 2, 1, "LIB", 0), Symbol.Type.LABEL, null, true));
        table.define(new Symbol("KEPT", new SourceInfo(LIB, 3, 1, "LIB", 0), Symbol.Type.LABEL, null, false));
        table.leaveScope();

        SourceInfo at = new SourceInfo(LIB, 5, 6, "LIB", 0);
        assertThat(table.resolve("P.SHOWN", at).found()).hasValueSatisfying(found -> {
            assertThat(found.qualifiedName()).isEqualTo("LIB.P.SHOWN");
            assertThat(found.scope()).isEqualTo("LIB.P");
        });
        assertThat(table.resolve("P.KEPT", at)).isInstanceOfSatisfying(Resolution.Missing.class,
                missing -> assertThat(missing.explanation()).isEqualTo("'KEPT' of P is not marked EXPORT."));

        table.setCurrentModule("");
        assertThat(table.resolve("LIB.P.SHOWN", new SourceInfo(MAIN, 1, 6, "", 0)).found())
                .map(ResolvedSymbol::qualifiedName).contains("LIB.P.SHOWN");
    }

    @Test
    void aNameWithADotIsReportedAndNotFiled() {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        SymbolTable dotted = new SymbolTable(diagnostics);
        dotted.registerModule("", MAIN);
        dotted.setCurrentModule("");

        Optional<Symbol> existing = dotted.define(new Symbol("X.Y", new SourceInfo(MAIN, 4, 1, "", 0), Symbol.Type.LABEL));

        assertThat(existing).isEmpty();
        assertThat(diagnostics.getDiagnostics()).singleElement().satisfies(d -> {
            assertThat(d.lineNumber()).isEqualTo(4);
            assertThat(d.message()).contains("'X.Y'").contains("a name is one segment");
        });
        assertThat(dotted.resolve("X.Y", new SourceInfo(MAIN, 5, 1, "", 0)).found()).isEmpty();
    }

}
