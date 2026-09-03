package org.evochora.compiler.frontend.postprocess;

import org.evochora.compiler.frontend.semantics.ScopeTracker;
import org.evochora.compiler.features.ctx.PushCtxNode;
import org.evochora.compiler.features.ctx.PopCtxNode;
import org.evochora.compiler.features.reg.RegNode;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.InstructionNode;
import org.evochora.compiler.model.ast.NumberLiteralNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.model.ast.TypedLiteralNode;
import org.evochora.compiler.features.define.DefineNode;
import org.evochora.compiler.frontend.module.ModuleContextTracker;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.TestRegistries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link AstPostProcessor} class.
 * Verifies that register aliases are correctly resolved and replaced in the AST.
 */
@Tag("unit")
class AstPostProcessorTest {

    private AstPostProcessor processor;
    private SymbolTable symbolTable;
    private DiagnosticsEngine diagnostics;

    @BeforeEach
    void setUp() {
        diagnostics = new DiagnosticsEngine();
        symbolTable = new SymbolTable(diagnostics);

        // Register a default module and set it as current
        symbolTable.registerModule("TEST", "test.s");
        symbolTable.setCurrentModule("TEST");

        processor = new AstPostProcessor(symbolTable, new ModuleContextTracker(symbolTable), new ScopeTracker(symbolTable), TestRegistries.postProcessRegistry());

        // Register aliases as REGISTER_ALIAS_DATA symbols with RegNode on the Symbol's node field
        RegNode counterReg = new RegNode("COUNTER", "%DR0", createSourceInfo());
        RegNode tmpReg = new RegNode("TMP", "%PDR0", createSourceInfo());
        RegNode posReg = new RegNode("POS", "%DR1", createSourceInfo());
        symbolTable.define(new Symbol("COUNTER", createSourceInfo(), Symbol.Type.REGISTER_ALIAS_DATA, counterReg));
        symbolTable.define(new Symbol("TMP", createSourceInfo(), Symbol.Type.REGISTER_ALIAS_DATA, tmpReg));
        symbolTable.define(new Symbol("POS", createSourceInfo(), Symbol.Type.REGISTER_ALIAS_DATA, posReg));
    }

    @Test
    void testProcess_NoAliases_ReturnsOriginalAst() {
        // Create a simple AST with no aliases
        IdentifierNode idNode = new IdentifierNode("SOME_LABEL", createSourceInfo());

        // Create a simple AST structure - just use the identifier node
        AstNode result = processor.process(idNode);

        // Should return the original AST unchanged
        assertThat(result).isSameAs(idNode);
    }

    @Test
    void testProcess_RegisterAlias_ReplacesIdentifierWithRegisterNode() {
        // Create an identifier that should be resolved as a register alias
        IdentifierNode idNode = new IdentifierNode("COUNTER", createSourceInfo());

        // Symbol already defined in setUp with RegNode — just process
        AstNode result = processor.process(idNode);

        // Should be replaced with a RegisterNode
        assertThat(result).isInstanceOf(RegisterNode.class);
        RegisterNode registerNode = (RegisterNode) result;

        // Verify the replacement details
        assertThat(registerNode.name()).isEqualTo("%DR0");
        assertThat(registerNode.originalAlias()).isEqualTo("COUNTER");
        assertThat(registerNode.isAlias()).isTrue();
    }

    @Test
    void testProcess_MultipleAliases_AllReplacedCorrectly() {
        // Create multiple identifiers that should be resolved
        IdentifierNode counterNode = new IdentifierNode("COUNTER", createSourceInfo());
        IdentifierNode tmpNode = new IdentifierNode("TMP", createSourceInfo());
        IdentifierNode posNode = new IdentifierNode("POS", createSourceInfo());

        // Symbols already defined in setUp with RegNode — just create the AST
        InstructionNode instruction = new InstructionNode(
            "SETI", List.of(counterNode, tmpNode, posNode), createSourceInfo()
        );

        // Process the AST
        AstNode result = processor.process(instruction);

        // Should be an InstructionNode with replaced arguments
        assertThat(result).isInstanceOf(InstructionNode.class);
        InstructionNode resultInstruction = (InstructionNode) result;

        // All arguments should be RegisterNodes now
        assertThat(resultInstruction.arguments()).hasSize(3);
        assertThat(resultInstruction.arguments().get(0)).isInstanceOf(RegisterNode.class);
        assertThat(resultInstruction.arguments().get(1)).isInstanceOf(RegisterNode.class);
        assertThat(resultInstruction.arguments().get(2)).isInstanceOf(RegisterNode.class);

        // Verify the specific replacements
        RegisterNode counterReg = (RegisterNode) resultInstruction.arguments().get(0);
        RegisterNode tmpReg = (RegisterNode) resultInstruction.arguments().get(1);
        RegisterNode posReg = (RegisterNode) resultInstruction.arguments().get(2);

        assertThat(counterReg.name()).isEqualTo("%DR0");
        assertThat(tmpReg.name()).isEqualTo("%PDR0");
        assertThat(posReg.name()).isEqualTo("%DR1");
    }

    @Test
    void testProcess_LabelIdentifier_ReplacedByQualifiedName() {
        // Create an identifier that names a label, a definition without a binding
        IdentifierNode idNode = new IdentifierNode("SOME_LABEL", createSourceInfo());

        // Add it as a LABEL symbol (not ALIAS)
        symbolTable.define(new Symbol("SOME_LABEL", createSourceInfo(), Symbol.Type.LABEL));

        // Process the AST
        AstNode result = processor.process(idNode);

        // The identifier now carries the label's qualified name, with the source location kept
        assertThat(result).isInstanceOf(IdentifierNode.class);
        assertThat(((IdentifierNode) result).text()).isEqualTo("TEST.SOME_LABEL");
        assertThat(((IdentifierNode) result).sourceInfo()).isEqualTo(idNode.sourceInfo());
    }

    @Test
    void testProcess_ExportedLabelOfImportedModule_ReplacedByQualifiedName() {
        // Given: an exported label in module LIB and an import alias LIB in module MAIN
        DiagnosticsEngine diags = new DiagnosticsEngine();
        SymbolTable st = new SymbolTable(diags);
        st.registerModule("LIB", "lib.s");
        st.registerModule("MAIN", "main.s");
        st.setCurrentModule("LIB");
        st.define(new Symbol("TARGET", new SourceInfo("lib.s", 1, 0), Symbol.Type.LABEL, null, true));
        st.setCurrentModule("MAIN");
        st.getModuleScope("MAIN").orElseThrow().imports().put("LIB", "LIB");
        AstPostProcessor mainProcessor = new AstPostProcessor(st, new ModuleContextTracker(st), new ScopeTracker(st), TestRegistries.postProcessRegistry());

        // When: main.s refers to the label through the alias
        IdentifierNode reference = new IdentifierNode("LIB.TARGET", new SourceInfo("main.s", 1, 0));
        AstNode result = mainProcessor.process(reference);

        // Then: the reference carries the label's qualified name, which here equals what was written
        assertThat(result).isInstanceOf(IdentifierNode.class);
        assertThat(((IdentifierNode) result).text()).isEqualTo("LIB.TARGET");
    }

    @Test
    void testProcess_NonExportedLabelOfImportedModule_NotReplaced() {
        // Given: a label in module LIB that is not exported
        DiagnosticsEngine diags = new DiagnosticsEngine();
        SymbolTable st = new SymbolTable(diags);
        st.registerModule("LIB", "lib.s");
        st.registerModule("MAIN", "main.s");
        st.setCurrentModule("LIB");
        st.define(new Symbol("PRIVATE", new SourceInfo("lib.s", 1, 0), Symbol.Type.LABEL));
        st.setCurrentModule("MAIN");
        st.getModuleScope("MAIN").orElseThrow().imports().put("LIB", "LIB");
        AstPostProcessor mainProcessor = new AstPostProcessor(st, new ModuleContextTracker(st), new ScopeTracker(st), TestRegistries.postProcessRegistry());

        // When: main.s refers to it through the alias
        IdentifierNode reference = new IdentifierNode("LIB.PRIVATE", new SourceInfo("main.s", 1, 0));
        AstNode result = mainProcessor.process(reference);

        // Then: the symbol table does not resolve it, so the identifier stays as written
        assertThat(result).isSameAs(reference);
    }

    @Test
    void testProcess_UnknownIdentifier_NotReplaced() {
        // Create an identifier that doesn't exist in the symbol table
        IdentifierNode idNode = new IdentifierNode("UNKNOWN", createSourceInfo());

        // Process the AST
        AstNode result = processor.process(idNode);

        // Should NOT be replaced
        assertThat(result).isSameAs(idNode);
    }

    @Test
    void testProcess_ModuleAlias_ReplacedByQualifiedName() {
        // A module alias is a definition without a binding, so a reference to it is qualified
        // like any other symbol; it is never replaced by a register or a value
        IdentifierNode idNode = new IdentifierNode("SOME_ALIAS", createSourceInfo());
        symbolTable.define(new Symbol("SOME_ALIAS", createSourceInfo(), Symbol.Type.MODULE_ALIAS));

        AstNode result = processor.process(idNode);

        assertThat(result).isInstanceOf(IdentifierNode.class);
        assertThat(((IdentifierNode) result).text()).isEqualTo("TEST.SOME_ALIAS");
    }

    @Test
    void testProcess_ComplexAst_AliasAndLabelReplaced() {
        // Create a complex AST with mixed content
        IdentifierNode counterNode = new IdentifierNode("COUNTER", createSourceInfo());
        IdentifierNode labelNode = new IdentifierNode("SOME_LABEL", createSourceInfo());
        NumberLiteralNode numberNode = new NumberLiteralNode(42, createSourceInfo());

        // COUNTER already defined as ALIAS in setUp; add SOME_LABEL as LABEL
        symbolTable.define(new Symbol("SOME_LABEL", createSourceInfo(), Symbol.Type.LABEL));

        // Create instruction with mixed arguments
        InstructionNode instruction = new InstructionNode(
            "SETI", List.of(counterNode, labelNode, numberNode), createSourceInfo()
        );

        // Process the AST
        AstNode result = processor.process(instruction);

        // Should be an InstructionNode
        assertThat(result).isInstanceOf(InstructionNode.class);
        InstructionNode resultInstruction = (InstructionNode) result;

        // The alias becomes a register, the label reference its qualified name, the literal stays
        assertThat(resultInstruction.arguments().get(0)).isInstanceOf(RegisterNode.class);
        assertThat(resultInstruction.arguments().get(1)).isInstanceOf(IdentifierNode.class);
        assertThat(((IdentifierNode) resultInstruction.arguments().get(1)).text()).isEqualTo("TEST.SOME_LABEL");
        assertThat(resultInstruction.arguments().get(2)).isSameAs(numberNode); // Not replaced

        // Verify the alias replacement
        RegisterNode counterReg = (RegisterNode) resultInstruction.arguments().get(0);
        assertThat(counterReg.name()).isEqualTo("%DR0");
        assertThat(counterReg.originalAlias()).isEqualTo("COUNTER");
    }

    @Test
    void testProcess_RegisterNode_NotReplaced() {
        // Create a RegisterNode (should not be processed)
        RegisterNode registerNode = new RegisterNode(
            "%DR0",
            createSourceInfo()
        );

        // Process the AST
        AstNode result = processor.process(registerNode);

        // Should NOT be replaced
        assertThat(result).isSameAs(registerNode);
    }

    @Test
    void testProcess_NumberLiteralNode_NotReplaced() {
        // Create a NumberLiteralNode (should not be processed)
        NumberLiteralNode numberNode = new NumberLiteralNode(42, createSourceInfo());

        // Process the AST
        AstNode result = processor.process(numberNode);

        // Should NOT be replaced
        assertThat(result).isSameAs(numberNode);
    }

    @Test
    void testProcess_AliasWithoutRegNode_NotBoundToRegister() {
        // Create a fresh symbol table with an ALIAS symbol that has no RegNode (node=null)
        DiagnosticsEngine freshDiags = new DiagnosticsEngine();
        SymbolTable freshSt = new SymbolTable(freshDiags);
        freshSt.registerModule("TEST", "test.s");
        freshSt.setCurrentModule("TEST");
        freshSt.define(new Symbol("ORPHAN", createSourceInfo(), Symbol.Type.MODULE_ALIAS));

        AstPostProcessor freshProcessor = new AstPostProcessor(freshSt, new ModuleContextTracker(freshSt), new ScopeTracker(freshSt), TestRegistries.postProcessRegistry());

        IdentifierNode idNode = new IdentifierNode("ORPHAN", createSourceInfo());
        AstNode result = freshProcessor.process(idNode);

        // Not replaced by a register: the symbol has no node that offers a binding. Like any
        // other symbol without one, it is referred to by its qualified name.
        assertThat(result).isInstanceOf(IdentifierNode.class);
        assertThat(((IdentifierNode) result).text()).isEqualTo("TEST.ORPHAN");
    }

    @Test
    void testProcess_SourceInfoPreserved() {
        // Create an identifier with specific source info — COUNTER already in setUp
        IdentifierNode idNode = new IdentifierNode("COUNTER", createSourceInfo());

        // Process the AST
        AstNode result = processor.process(idNode);

        // Should be replaced
        assertThat(result).isInstanceOf(RegisterNode.class);
        RegisterNode registerNode = (RegisterNode) result;

        // Source info should be preserved
        SourceInfo sourceInfo = registerNode.sourceInfo();
        assertThat(sourceInfo.fileName()).isEqualTo("test.s");
        assertThat(sourceInfo.lineNumber()).isEqualTo(10);
        assertThat(sourceInfo.columnNumber()).isEqualTo(5);
    }

    @Test
    void testProcess_TokenInfoCorrect() {
        // Create an identifier — COUNTER already in setUp
        IdentifierNode idNode = new IdentifierNode("COUNTER", createSourceInfo());

        // Process the AST
        AstNode result = processor.process(idNode);

        // Should be replaced
        assertThat(result).isInstanceOf(RegisterNode.class);
        RegisterNode registerNode = (RegisterNode) result;

        // Verify name and source info
        assertThat(registerNode.name()).isEqualTo("%DR0");
        assertThat(registerNode.sourceInfo().lineNumber()).isEqualTo(10);
        assertThat(registerNode.sourceInfo().columnNumber()).isEqualTo(5);
        assertThat(registerNode.sourceInfo().fileName()).isEqualTo("test.s");
    }

    @Test
    void testProcess_ConstantResolvedWithModuleContext() {
        // Set up two modules with different values for the same constant name
        String modAChain = "MOD_A";
        String modBChain = "MOD_B";
        String mainChain = "MAIN";

        DiagnosticsEngine diags = new DiagnosticsEngine();
        SymbolTable st = new SymbolTable(diags);
        st.registerModule(mainChain, "/main.evo");
        st.registerModule(modAChain, "/mod_a.evo");
        st.registerModule(modBChain, "/mod_b.evo");
        st.setCurrentModule(mainChain);

        SourceInfo siA = new SourceInfo("/mod_a.evo", 1, 1);
        SourceInfo siB = new SourceInfo("/mod_b.evo", 1, 1);
        TypedLiteralNode valueA = new TypedLiteralNode("DATA", 10, siA);
        TypedLiteralNode valueB = new TypedLiteralNode("DATA", 1, siB);
        DefineNode defineA = new DefineNode("STEP", siA, valueA);
        DefineNode defineB = new DefineNode("STEP", siB, valueB);

        // Define STEP=10 in module A context, with the defining node as the symbol's node,
        // as the analysis handler of .DEFINE does
        st.setCurrentModule(modAChain);
        st.define(new Symbol("STEP", siA, Symbol.Type.CONSTANT, defineA));

        // Define STEP=1 in module B context
        st.setCurrentModule(modBChain);
        st.define(new Symbol("STEP", siB, Symbol.Type.CONSTANT, defineB));

        st.setCurrentModule(mainChain);

        IdentifierNode useA = new IdentifierNode("STEP", new SourceInfo("/mod_a.evo", 2, 1));
        IdentifierNode useB = new IdentifierNode("STEP", new SourceInfo("/mod_b.evo", 2, 1));

        InstructionNode instrA = new InstructionNode(
                "SETI", List.of(new RegisterNode("%DR0", createSourceInfo()), useA),
                new SourceInfo("/mod_a.evo", 2, 1));
        InstructionNode instrB = new InstructionNode(
                "SETI", List.of(new RegisterNode("%DR1", createSourceInfo()), useB),
                new SourceInfo("/mod_b.evo", 2, 1));

        // Use ModuleContextTracker with alias chains via PushCtxNode
        ModuleContextTracker tracker = new ModuleContextTracker(st);
        AstPostProcessor moduleProcessor = new AstPostProcessor(st, tracker, new ScopeTracker(st), TestRegistries.postProcessRegistry());

        List<AstNode> nodes = List.of(
                new PushCtxNode("/mod_a.evo", modAChain), defineA, instrA, new PopCtxNode(),
                new PushCtxNode("/mod_b.evo", modBChain), defineB, instrB, new PopCtxNode()
        );
        List<AstNode> results = new ArrayList<>();
        for (AstNode node : nodes) {
            results.add(moduleProcessor.process(node));
        }

        InstructionNode resultA = (InstructionNode) results.get(2);
        InstructionNode resultB = (InstructionNode) results.get(6);

        // Module A should have STEP=10
        assertThat(resultA.arguments().get(1)).isInstanceOf(TypedLiteralNode.class);
        assertThat(((TypedLiteralNode) resultA.arguments().get(1)).value()).isEqualTo(10);

        // Module B should have STEP=1
        assertThat(resultB.arguments().get(1)).isInstanceOf(TypedLiteralNode.class);
        assertThat(((TypedLiteralNode) resultB.arguments().get(1)).value()).isEqualTo(1);
    }

    @Test
    void testProcess_SingleFileConstantResolutionStillWorks() {
        // Verify single-file mode (no module context) still resolves constants
        TypedLiteralNode constValue = new TypedLiteralNode("DATA", 99, new SourceInfo("test.s", 1, 1));
        DefineNode defineNode = new DefineNode("MY_CONST", createSourceInfo(), constValue);
        symbolTable.define(new Symbol("MY_CONST", createSourceInfo(), Symbol.Type.CONSTANT, defineNode));

        IdentifierNode useNode = new IdentifierNode("MY_CONST", createSourceInfo());
        InstructionNode instr = new InstructionNode(
                "SETI", List.of(new RegisterNode("%DR0", createSourceInfo()), useNode),
                createSourceInfo());

        // Process each node individually (matching the real Compiler pattern)
        processor.process(defineNode);
        AstNode resultInstr = processor.process(instr);

        assertThat(resultInstr).isInstanceOf(InstructionNode.class);
        assertThat(((InstructionNode) resultInstr).arguments().get(1)).isInstanceOf(TypedLiteralNode.class);
        assertThat(((TypedLiteralNode) ((InstructionNode) resultInstr).arguments().get(1)).value()).isEqualTo(99);
    }

    private SourceInfo createSourceInfo() {
        return new SourceInfo("test.s", 10, 5);
    }
}
