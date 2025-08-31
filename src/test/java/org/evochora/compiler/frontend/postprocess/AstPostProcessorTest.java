package org.evochora.compiler.frontend.postprocess;

import org.evochora.compiler.frontend.parser.ast.*;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.semantics.SymbolTable;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

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
    private Map<String, String> registerAliases;
    private DiagnosticsEngine diagnostics;

    @BeforeEach
    void setUp() {
        diagnostics = new DiagnosticsEngine();
        symbolTable = new SymbolTable(diagnostics);
        registerAliases = new HashMap<>();
        
        // Set up some test register aliases
        registerAliases.put("COUNTER", "%DR0");
        registerAliases.put("TMP", "%PR0");
        registerAliases.put("POS", "%DR1");
        
        processor = new AstPostProcessor(symbolTable, registerAliases);
    }

    @Test
    void testProcess_NoAliases_ReturnsOriginalAst() {
        // Create a simple AST with no aliases
        IdentifierNode idNode = new IdentifierNode(createToken("SOME_LABEL", TokenType.IDENTIFIER));
        
        // Create a simple AST structure - just use the identifier node
        AstNode result = processor.process(idNode);
        
        // Should return the original AST unchanged
        assertThat(result).isSameAs(idNode);
    }

    @Test
    void testProcess_RegisterAlias_ReplacesIdentifierWithRegisterNode() {
        // Create an identifier that should be resolved as a register alias
        IdentifierNode idNode = new IdentifierNode(createToken("COUNTER", TokenType.IDENTIFIER));
        
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
        assertThat(registerNode.registerToken().text()).isEqualTo("%DR0");
        assertThat(registerNode.registerToken().type()).isEqualTo(TokenType.REGISTER);
    }

    @Test
    void testProcess_MultipleAliases_AllReplacedCorrectly() {
        // Create multiple identifiers that should be resolved
        IdentifierNode counterNode = new IdentifierNode(createToken("COUNTER", TokenType.IDENTIFIER));
        IdentifierNode tmpNode = new IdentifierNode(createToken("TMP", TokenType.IDENTIFIER));
        IdentifierNode posNode = new IdentifierNode(createToken("POS", TokenType.IDENTIFIER));
        
        // Add all symbols to the symbol table
        symbolTable.define(new Symbol(createToken("COUNTER", TokenType.IDENTIFIER), Symbol.Type.ALIAS));
        symbolTable.define(new Symbol(createToken("TMP", TokenType.IDENTIFIER), Symbol.Type.ALIAS));
        symbolTable.define(new Symbol(createToken("POS", TokenType.IDENTIFIER), Symbol.Type.ALIAS));
        
        // Create a simple AST with these identifiers
        InstructionNode instruction = new InstructionNode(
            createToken("SETI", TokenType.OPCODE),
            List.of(counterNode, tmpNode, posNode)
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
        IdentifierNode idNode = new IdentifierNode(createToken("SOME_LABEL", TokenType.IDENTIFIER));
        
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
        IdentifierNode idNode = new IdentifierNode(createToken("UNKNOWN", TokenType.IDENTIFIER));
        
        // Process the AST
        AstNode result = processor.process(idNode);
        
        // Should NOT be replaced
        assertThat(result).isSameAs(idNode);
    }

    @Test
    void testProcess_AliasNotInRegisterAliases_NotReplaced() {
        // Create an identifier that is an alias but not in our register aliases map
        IdentifierNode idNode = new IdentifierNode(createToken("SOME_ALIAS", TokenType.IDENTIFIER));
        
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
        IdentifierNode counterNode = new IdentifierNode(createToken("COUNTER", TokenType.IDENTIFIER));
        IdentifierNode labelNode = new IdentifierNode(createToken("SOME_LABEL", TokenType.IDENTIFIER));
        NumberLiteralNode numberNode = new NumberLiteralNode(createToken("42", TokenType.NUMBER));
        
        // Add symbols to symbol table
        symbolTable.define(new Symbol(createToken("COUNTER", TokenType.IDENTIFIER), Symbol.Type.ALIAS));
        symbolTable.define(new Symbol(createToken("SOME_LABEL", TokenType.IDENTIFIER), Symbol.Type.LABEL));
        
        // Create instruction with mixed arguments
        InstructionNode instruction = new InstructionNode(
            createToken("SETI", TokenType.OPCODE),
            List.of(counterNode, labelNode, numberNode)
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
            createSourceInfo(),
            createToken("%DR0", TokenType.REGISTER)
        );
        
        // Process the AST
        AstNode result = processor.process(registerNode);
        
        // Should NOT be replaced
        assertThat(result).isSameAs(registerNode);
    }

    @Test
    void testProcess_NumberLiteralNode_NotReplaced() {
        // Create a NumberLiteralNode (should not be processed)
        NumberLiteralNode numberNode = new NumberLiteralNode(createToken("42", TokenType.NUMBER));
        
        // Process the AST
        AstNode result = processor.process(numberNode);
        
        // Should NOT be replaced
        assertThat(result).isSameAs(numberNode);
    }

    @Test
    void testProcess_EmptyRegisterAliases_NoReplacements() {
        // Create processor with no register aliases
        AstPostProcessor emptyProcessor = new AstPostProcessor(symbolTable, new HashMap<>());
        
        // Create an identifier that would be an alias
        IdentifierNode idNode = new IdentifierNode(createToken("COUNTER", TokenType.IDENTIFIER));
        symbolTable.define(new Symbol(createToken("COUNTER", TokenType.IDENTIFIER), Symbol.Type.ALIAS));
        
        // Process the AST
        AstNode result = emptyProcessor.process(idNode);
        
        // Should NOT be replaced (no aliases defined)
        assertThat(result).isSameAs(idNode);
    }

    @Test
    void testProcess_SourceInfoPreserved() {
        // Create an identifier with specific source info
        Token token = createToken("COUNTER", TokenType.IDENTIFIER);
        IdentifierNode idNode = new IdentifierNode(token);
        
        // Add to symbol table
        symbolTable.define(new Symbol(token, Symbol.Type.ALIAS));
        
        // Process the AST
        AstNode result = processor.process(idNode);
        
        // Should be replaced
        assertThat(result).isInstanceOf(RegisterNode.class);
        RegisterNode registerNode = (RegisterNode) result;
        
        // Source info should be preserved
        SourceInfo sourceInfo = registerNode.getSourceInfo();
        assertThat(sourceInfo.fileName()).isEqualTo("test.s");
        assertThat(sourceInfo.lineNumber()).isEqualTo(10);
        assertThat(sourceInfo.columnNumber()).isEqualTo(5);
    }

    @Test
    void testProcess_TokenInfoCorrect() {
        // Create an identifier
        Token token = createToken("COUNTER", TokenType.IDENTIFIER);
        IdentifierNode idNode = new IdentifierNode(token);
        
        // Add to symbol table
        symbolTable.define(new Symbol(token, Symbol.Type.ALIAS));
        
        // Process the AST
        AstNode result = processor.process(idNode);
        
        // Should be replaced
        assertThat(result).isInstanceOf(RegisterNode.class);
        RegisterNode registerNode = (RegisterNode) result;
        
        // Token should have correct type and text
        Token registerToken = registerNode.registerToken();
        assertThat(registerToken.type()).isEqualTo(TokenType.REGISTER);
        assertThat(registerToken.text()).isEqualTo("%DR0");
        assertThat(registerToken.value()).isNull(); // Registers don't have values
        assertThat(registerToken.line()).isEqualTo(10);
        assertThat(registerToken.column()).isEqualTo(5);
        assertThat(registerToken.fileName()).isEqualTo("test.s");
    }

    // Helper methods
    private Token createToken(String text, TokenType type) {
        return new Token(type, text, null, 10, 5, "test.s");
    }
    
    private SourceInfo createSourceInfo() {
        return new SourceInfo("test.s", 10, 5);
    }
}
