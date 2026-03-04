package org.evochora.compiler.frontend.postprocess;

import org.evochora.compiler.frontend.parser.ast.*;
import org.evochora.compiler.frontend.parser.features.reg.RegNode;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.InstructionNode;
import org.evochora.compiler.model.ast.NumberLiteralNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.model.ast.TypedLiteralNode;
import org.evochora.compiler.frontend.parser.features.def.DefineNode;
import org.evochora.compiler.frontend.semantics.ModuleContextTracker;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.semantics.SymbolTable;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        processor = new AstPostProcessor(symbolTable);

        // Register aliases by processing RegNode instances (self-extraction)
        processor.process(new RegNode(createToken("COUNTER", TokenType.IDENTIFIER), createToken("%DR0", TokenType.REGISTER)));
        processor.process(new RegNode(createToken("TMP", TokenType.IDENTIFIER), createToken("%PR0", TokenType.REGISTER)));
        processor.process(new RegNode(createToken("POS", TokenType.IDENTIFIER), createToken("%DR1", TokenType.REGISTER)));
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

        // Add the symbol to the symbol table
        symbolTable.define(new Symbol(createToken("COUNTER", TokenType.IDENTIFIER), Symbol.Type.ALIAS));

        // Process the AST
        AstNode result = processor.process(idNode);

        // Should be replaced with a RegisterNode
        assertThat(result).isInstanceOf(RegisterNode.class);
        RegisterNode registerNode = (RegisterNode) result;

        // Verify the replacement details
        assertThat(registerNode.getName()).isEqualTo("%DR0");
        assertThat(registerNode.getOriginalAlias()).isEqualTo("COUNTER");
        assertThat(registerNode.isAlias()).isTrue();
        assertThat(registerNode.getName()).isEqualTo("%DR0");
    }

    @Test
    void testProcess_MultipleAliases_AllReplacedCorrectly() {
        // Create multiple identifiers that should be resolved
        IdentifierNode counterNode = new IdentifierNode("COUNTER", createSourceInfo());
        IdentifierNode tmpNode = new IdentifierNode("TMP", createSourceInfo());
        IdentifierNode posNode = new IdentifierNode("POS", createSourceInfo());

        // Add all symbols to the symbol table
        symbolTable.define(new Symbol(createToken("COUNTER", TokenType.IDENTIFIER), Symbol.Type.ALIAS));
        symbolTable.define(new Symbol(createToken("TMP", TokenType.IDENTIFIER), Symbol.Type.ALIAS));
        symbolTable.define(new Symbol(createToken("POS", TokenType.IDENTIFIER), Symbol.Type.ALIAS));

        // Create a simple AST with these identifiers
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

        assertThat(counterReg.getName()).isEqualTo("%DR0");
        assertThat(tmpReg.getName()).isEqualTo("%PR0");
        assertThat(posReg.getName()).isEqualTo("%DR1");
    }

    @Test
    void testProcess_NonAliasIdentifier_NotReplaced() {
        // Create an identifier that is NOT an alias
        IdentifierNode idNode = new IdentifierNode("SOME_LABEL", createSourceInfo());

        // Add it as a LABEL symbol (not ALIAS)
        symbolTable.define(new Symbol(createToken("SOME_LABEL", TokenType.IDENTIFIER), Symbol.Type.LABEL));

        // Process the AST
        AstNode result = processor.process(idNode);

        // Should NOT be replaced
        assertThat(result).isSameAs(idNode);
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
    void testProcess_AliasNotInRegisterAliases_NotReplaced() {
        // Create an identifier that is an alias but not in our register aliases map
        IdentifierNode idNode = new IdentifierNode("SOME_ALIAS", createSourceInfo());

        // Add it as an ALIAS symbol
        symbolTable.define(new Symbol(createToken("SOME_ALIAS", TokenType.IDENTIFIER), Symbol.Type.ALIAS));

        // Process the AST
        AstNode result = processor.process(idNode);

        // Should NOT be replaced (not a register alias)
        assertThat(result).isSameAs(idNode);
    }

    @Test
    void testProcess_ComplexAst_OnlyAliasesReplaced() {
        // Create a complex AST with mixed content
        IdentifierNode counterNode = new IdentifierNode("COUNTER", createSourceInfo());
        IdentifierNode labelNode = new IdentifierNode("SOME_LABEL", createSourceInfo());
        NumberLiteralNode numberNode = new NumberLiteralNode(42, createSourceInfo());

        // Add symbols to symbol table
        symbolTable.define(new Symbol(createToken("COUNTER", TokenType.IDENTIFIER), Symbol.Type.ALIAS));
        symbolTable.define(new Symbol(createToken("SOME_LABEL", TokenType.IDENTIFIER), Symbol.Type.LABEL));

        // Create instruction with mixed arguments
        InstructionNode instruction = new InstructionNode(
            "SETI", List.of(counterNode, labelNode, numberNode), createSourceInfo()
        );

        // Process the AST
        AstNode result = processor.process(instruction);

        // Should be an InstructionNode
        assertThat(result).isInstanceOf(InstructionNode.class);
        InstructionNode resultInstruction = (InstructionNode) result;

        // Only the alias should be replaced
        assertThat(resultInstruction.arguments().get(0)).isInstanceOf(RegisterNode.class);
        assertThat(resultInstruction.arguments().get(1)).isSameAs(labelNode); // Not replaced
        assertThat(resultInstruction.arguments().get(2)).isSameAs(numberNode); // Not replaced

        // Verify the alias replacement
        RegisterNode counterReg = (RegisterNode) resultInstruction.arguments().get(0);
        assertThat(counterReg.getName()).isEqualTo("%DR0");
        assertThat(counterReg.getOriginalAlias()).isEqualTo("COUNTER");
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
    void testProcess_NoRegNodeProcessed_NoReplacements() {
        // Create processor without processing any RegNode
        AstPostProcessor emptyProcessor = new AstPostProcessor(symbolTable);

        // Create an identifier that would be an alias
        IdentifierNode idNode = new IdentifierNode("COUNTER", createSourceInfo());
        symbolTable.define(new Symbol(createToken("COUNTER", TokenType.IDENTIFIER), Symbol.Type.ALIAS));

        // Process the AST
        AstNode result = emptyProcessor.process(idNode);

        // Should NOT be replaced (no RegNode processed → no aliases known)
        assertThat(result).isSameAs(idNode);
    }

    @Test
    void testProcess_SourceInfoPreserved() {
        // Create an identifier with specific source info
        IdentifierNode idNode = new IdentifierNode("COUNTER", createSourceInfo());

        // Add to symbol table
        symbolTable.define(new Symbol(createToken("COUNTER", TokenType.IDENTIFIER), Symbol.Type.ALIAS));

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
        // Create an identifier
        IdentifierNode idNode = new IdentifierNode("COUNTER", createSourceInfo());

        // Add to symbol table
        symbolTable.define(new Symbol(createToken("COUNTER", TokenType.IDENTIFIER), Symbol.Type.ALIAS));

        // Process the AST
        AstNode result = processor.process(idNode);

        // Should be replaced
        assertThat(result).isInstanceOf(RegisterNode.class);
        RegisterNode registerNode = (RegisterNode) result;

        // Verify name and source info
        assertThat(registerNode.getName()).isEqualTo("%DR0");
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

        // Define STEP=10 in module A context
        st.setCurrentModule(modAChain);
        Token stepTokenA = new Token(TokenType.IDENTIFIER, "STEP", null, 1, 1, "/mod_a.evo");
        st.define(new Symbol(stepTokenA, Symbol.Type.CONSTANT));

        // Define STEP=1 in module B context
        st.setCurrentModule(modBChain);
        Token stepTokenB = new Token(TokenType.IDENTIFIER, "STEP", null, 1, 1, "/mod_b.evo");
        st.define(new Symbol(stepTokenB, Symbol.Type.CONSTANT));

        st.setCurrentModule(mainChain);

        TypedLiteralNode valueA = new TypedLiteralNode("DATA", 10, new SourceInfo("/mod_a.evo", 1, 1));
        TypedLiteralNode valueB = new TypedLiteralNode("DATA", 1, new SourceInfo("/mod_b.evo", 1, 1));

        DefineNode defineA = new DefineNode(stepTokenA, valueA);
        DefineNode defineB = new DefineNode(stepTokenB, valueB);

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
        AstPostProcessor moduleProcessor = new AstPostProcessor(st, tracker);

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
        Token nameToken = createToken("MY_CONST", TokenType.IDENTIFIER);
        symbolTable.define(new Symbol(nameToken, Symbol.Type.CONSTANT));

        TypedLiteralNode constValue = new TypedLiteralNode("DATA", 99, new SourceInfo("test.s", 1, 1));
        DefineNode defineNode = new DefineNode(nameToken, constValue);

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

    // Helper methods
    private Token createToken(String text, TokenType type) {
        return new Token(type, text, null, 10, 5, "test.s");
    }

    private SourceInfo createSourceInfo() {
        return new SourceInfo("test.s", 10, 5);
    }
}
