package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.PopCtxNode;
import org.evochora.compiler.frontend.parser.ast.PushCtxNode;
import org.evochora.compiler.frontend.parser.ast.SourceLocatable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ModuleContextTracker}.
 * Verifies that module context switching works correctly for
 * PushCtxNode, PopCtxNode, and SourceLocatable nodes.
 */
@Tag("unit")
class ModuleContextTrackerTest {

    private static final ModuleId MAIN = new ModuleId("/main.evo");
    private static final ModuleId MATH = new ModuleId("/modules/math.evo");
    private static final ModuleId MOVE = new ModuleId("/modules/movement.evo");

    private SymbolTable symbolTable;
    private Map<String, ModuleId> fileToModule;
    private ModuleContextTracker tracker;

    @BeforeEach
    void setUp() {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        symbolTable = new SymbolTable(diagnostics);

        symbolTable.registerModule(MAIN, MAIN.path());
        symbolTable.registerModule(MATH, MATH.path());
        symbolTable.registerModule(MOVE, MOVE.path());
        symbolTable.setCurrentModule(MAIN);

        fileToModule = new HashMap<>();
        fileToModule.put(MAIN.path(), MAIN);
        fileToModule.put(MATH.path(), MATH);
        fileToModule.put(MOVE.path(), MOVE);

        tracker = new ModuleContextTracker(symbolTable, fileToModule);
    }

    @Test
    void pushCtxWithTargetPath_switchesToTargetModule() {
        tracker.handleNode(new PushCtxNode(MATH.path()));

        assertThat(symbolTable.getCurrentModuleId()).isEqualTo(MATH);
    }

    @Test
    void pushCtxWithoutTargetPath_keepsCurrentModule() {
        tracker.handleNode(new PushCtxNode());

        assertThat(symbolTable.getCurrentModuleId()).isEqualTo(MAIN);
    }

    @Test
    void popCtx_restoresPreviousModule() {
        tracker.handleNode(new PushCtxNode(MATH.path()));
        assertThat(symbolTable.getCurrentModuleId()).isEqualTo(MATH);

        tracker.handleNode(new PopCtxNode());
        assertThat(symbolTable.getCurrentModuleId()).isEqualTo(MAIN);
    }

    @Test
    void sourceLocatable_switchesModuleByFileName() {
        AstNode nodeFromMath = new StubSourceLocatable(MATH.path());
        tracker.handleNode(nodeFromMath);

        assertThat(symbolTable.getCurrentModuleId()).isEqualTo(MATH);
    }

    @Test
    void sourceLocatable_unknownFile_noSwitch() {
        AstNode nodeFromUnknown = new StubSourceLocatable("/unknown.evo");
        tracker.handleNode(nodeFromUnknown);

        assertThat(symbolTable.getCurrentModuleId()).isEqualTo(MAIN);
    }

    @Test
    void nestedPushPop_importThenSource_restoresCorrectly() {
        // Simulate: .IMPORT math.evo → .SOURCE constants.evo → Pop SOURCE → Pop IMPORT
        tracker.handleNode(new PushCtxNode(MATH.path()));       // enter math
        assertThat(symbolTable.getCurrentModuleId()).isEqualTo(MATH);

        tracker.handleNode(new PushCtxNode());                  // enter .SOURCE (no target)
        assertThat(symbolTable.getCurrentModuleId()).isEqualTo(MATH);

        tracker.handleNode(new PopCtxNode());                   // leave .SOURCE
        assertThat(symbolTable.getCurrentModuleId()).isEqualTo(MATH);

        tracker.handleNode(new PopCtxNode());                   // leave .IMPORT
        assertThat(symbolTable.getCurrentModuleId()).isEqualTo(MAIN);
    }

    @Test
    void twoSequentialImports_correctContextPerBlock() {
        // .IMPORT math.evo
        tracker.handleNode(new PushCtxNode(MATH.path()));
        assertThat(symbolTable.getCurrentModuleId()).isEqualTo(MATH);
        tracker.handleNode(new PopCtxNode());
        assertThat(symbolTable.getCurrentModuleId()).isEqualTo(MAIN);

        // .IMPORT movement.evo
        tracker.handleNode(new PushCtxNode(MOVE.path()));
        assertThat(symbolTable.getCurrentModuleId()).isEqualTo(MOVE);
        tracker.handleNode(new PopCtxNode());
        assertThat(symbolTable.getCurrentModuleId()).isEqualTo(MAIN);
    }

    @Test
    void emptyFileToModule_noSwitchEver() {
        ModuleContextTracker emptyTracker = new ModuleContextTracker(symbolTable, new HashMap<>());

        emptyTracker.handleNode(new PushCtxNode(MATH.path()));
        assertThat(symbolTable.getCurrentModuleId()).isEqualTo(MAIN);

        emptyTracker.handleNode(new StubSourceLocatable(MATH.path()));
        assertThat(symbolTable.getCurrentModuleId()).isEqualTo(MAIN);

        emptyTracker.handleNode(new PopCtxNode());
        assertThat(symbolTable.getCurrentModuleId()).isEqualTo(MAIN);
    }

    @Test
    void popCtxOnEmptyStack_noException() {
        // Should not throw even without a prior push
        tracker.handleNode(new PopCtxNode());
        assertThat(symbolTable.getCurrentModuleId()).isEqualTo(MAIN);
    }

    /**
     * Minimal SourceLocatable stub for testing.
     */
    private record StubSourceLocatable(String fileName) implements AstNode, SourceLocatable {
        @Override
        public String getSourceFileName() {
            return fileName;
        }

        @Override
        public List<AstNode> getChildren() {
            return List.of();
        }
    }
}
