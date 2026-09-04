package org.evochora.compiler.frontend.module;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.features.ctx.PopCtxNode;
import org.evochora.compiler.features.ctx.PushCtxNode;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.NumberLiteralNode;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ModuleContextTracker}.
 * Verifies that module context switching works correctly for
 * PushCtxNode and PopCtxNode nodes using alias chains.
 */
@Tag("unit")
class ModuleContextTrackerTest {

    private static final String MAIN = "MAIN";
    private static final String MATH = "MATH";
    private static final String MOVE = "MOVE";

    private SymbolTable symbolTable;
    private ModuleContextTracker tracker;

    @BeforeEach
    void setUp() {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        symbolTable = new SymbolTable(diagnostics);

        symbolTable.registerModule(MAIN, "/main.evo");
        symbolTable.registerModule(MATH, "/modules/math.evo");
        symbolTable.registerModule(MOVE, "/modules/movement.evo");
        symbolTable.setCurrentModule(MAIN);

        tracker = new ModuleContextTracker(symbolTable);
    }

    @Test
    void pushCtxWithAliasChain_switchesToTargetModule() {
        tracker.handleNode(new PushCtxNode("/modules/math.evo", MATH));

        assertThat(symbolTable.getCurrentAliasChain()).isEqualTo(MATH);
    }

    @Test
    void pushCtxWithoutAliasChain_keepsCurrentModule() {
        // .SOURCE: null alias chain preserves parent context
        tracker.handleNode(new PushCtxNode("/some/source.evo", null));

        assertThat(symbolTable.getCurrentAliasChain()).isEqualTo(MAIN);
    }

    @Test
    void popCtx_restoresPreviousModule() {
        tracker.handleNode(new PushCtxNode("/modules/math.evo", MATH));
        assertThat(symbolTable.getCurrentAliasChain()).isEqualTo(MATH);

        tracker.handleNode(new PopCtxNode());
        assertThat(symbolTable.getCurrentAliasChain()).isEqualTo(MAIN);
    }

    @Test
    void nestedPushPop_importThenSource_restoresCorrectly() {
        // Simulate: .IMPORT math.evo → .SOURCE constants.evo → Pop SOURCE → Pop IMPORT
        tracker.handleNode(new PushCtxNode("/modules/math.evo", MATH));
        assertThat(symbolTable.getCurrentAliasChain()).isEqualTo(MATH);

        tracker.handleNode(new PushCtxNode("/constants.evo", null)); // .SOURCE (no alias chain)
        assertThat(symbolTable.getCurrentAliasChain()).isEqualTo(MATH);

        tracker.handleNode(new PopCtxNode());                        // leave .SOURCE
        assertThat(symbolTable.getCurrentAliasChain()).isEqualTo(MATH);

        tracker.handleNode(new PopCtxNode());                        // leave .IMPORT
        assertThat(symbolTable.getCurrentAliasChain()).isEqualTo(MAIN);
    }

    @Test
    void twoSequentialImports_correctContextPerBlock() {
        // .IMPORT math.evo
        tracker.handleNode(new PushCtxNode("/modules/math.evo", MATH));
        assertThat(symbolTable.getCurrentAliasChain()).isEqualTo(MATH);
        tracker.handleNode(new PopCtxNode());
        assertThat(symbolTable.getCurrentAliasChain()).isEqualTo(MAIN);

        // .IMPORT movement.evo
        tracker.handleNode(new PushCtxNode("/modules/movement.evo", MOVE));
        assertThat(symbolTable.getCurrentAliasChain()).isEqualTo(MOVE);
        tracker.handleNode(new PopCtxNode());
        assertThat(symbolTable.getCurrentAliasChain()).isEqualTo(MAIN);
    }

    @Test
    void aWholeTraversalLeavesTheModuleWhereItFoundIt() {
        // The phases that walk the AST rely on this: whatever module is in context before a
        // traversal is in context again after it, so no phase has to reset the table for the next.
        symbolTable.registerModule(MATH + "." + MOVE, "/modules/movement.evo");
        SourceInfo src = new SourceInfo("/main.evo", 1, 1);
        List<AstNode> program = List.of(
                new NumberLiteralNode(1, src),
                new PushCtxNode("/modules/math.evo", MATH),           // .IMPORT math
                new NumberLiteralNode(2, src),
                new PushCtxNode("/modules/movement.evo", MATH + "." + MOVE), // nested .IMPORT
                new PushCtxNode("/constants.evo", null),              // .SOURCE inside it
                new NumberLiteralNode(3, src),
                new PopCtxNode(),
                new PopCtxNode(),
                new PopCtxNode(),
                new PushCtxNode("/modules/movement.evo", MOVE),       // sibling .IMPORT
                new PopCtxNode(),
                new NumberLiteralNode(4, src));

        for (AstNode node : program) {
            tracker.handleNode(node);
        }

        assertThat(symbolTable.getCurrentAliasChain()).isEqualTo(MAIN);
    }

    @Test
    void popCtxOnEmptyStack_noException() {
        // Should not throw even without a prior push
        tracker.handleNode(new PopCtxNode());
        assertThat(symbolTable.getCurrentAliasChain()).isEqualTo(MAIN);
    }
}
