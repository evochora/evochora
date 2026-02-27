/*
 * SPDX-FileCopyrightText: 2024-2024 EvoChora contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evochora.compiler.frontend;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.irgen.IrConverterRegistry;
import org.evochora.compiler.frontend.irgen.IrGenerator;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.semantics.SemanticAnalyzer;
import org.evochora.compiler.frontend.semantics.SymbolTable;
import org.evochora.compiler.ir.*;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;

public class IrGeneratorTest {

    @BeforeAll
    static void setup() {
        // We need to initialize the instruction set before we can use the compiler.
        Instruction.init();
    }

    private IrProgram compileToIr(String source) {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();

        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        if (diagnostics.hasErrors()) {
            fail("Lexer errors: " + diagnostics.summary());
        }

        Parser parser = new Parser(tokens, diagnostics);
        List<AstNode> ast = parser.parse();
        if (diagnostics.hasErrors()) {
            fail("Parser errors: " + diagnostics.summary());
        }

        SymbolTable symbolTable = new SymbolTable(diagnostics);
        new SemanticAnalyzer(diagnostics, symbolTable).analyze(ast);
        if (diagnostics.hasErrors()) {
            fail("Semantic analysis errors: " + diagnostics.summary());
        }

        IrConverterRegistry registry = IrConverterRegistry.initializeWithDefaults();
        IrGenerator irGen = new IrGenerator(diagnostics, registry);
        IrProgram ir = irGen.generate(ast, "TestProg");
        if (diagnostics.hasErrors()) {
            fail("IR generation errors: " + diagnostics.summary());
        }
        return ir;
    }

    @Test
    @Tag("unit")
    void generatesIrForNewCallSyntax() {
        String src = """
            .PROC myProc REF rA VAL v1
                CALL myProc REF %DR1 VAL 42
            .ENDP
            """;
        IrProgram ir = compileToIr(src);
        Optional<IrInstruction> callInstructionOpt = ir.items().stream()
                .filter(IrInstruction.class::isInstance)
                .map(IrInstruction.class::cast)
                .filter(i -> "CALL".equalsIgnoreCase(i.opcode()))
                .findFirst();

        assertTrue(callInstructionOpt.isPresent(), "CALL instruction not found in IR");
        IrInstruction callInstruction = callInstructionOpt.get();

        assertEquals(1, callInstruction.operands().size());
        assertInstanceOf(IrLabelRef.class, callInstruction.operands().get(0));
        assertEquals("myProc", ((IrLabelRef) callInstruction.operands().get(0)).labelName());

        assertEquals(1, callInstruction.refOperands().size());
        assertInstanceOf(IrReg.class, callInstruction.refOperands().get(0));
        assertEquals("%DR1", ((IrReg) callInstruction.refOperands().get(0)).name());

        assertEquals(1, callInstruction.valOperands().size());
        assertInstanceOf(IrImm.class, callInstruction.valOperands().get(0));
        assertEquals(42, ((IrImm) callInstruction.valOperands().get(0)).value());
    }

    @Test
    @Tag("unit")
    void generatesIrForRefCallSyntax() {
        String src = """
            .PROC oldProc REF p1
                CALL oldProc REF p1
            .ENDP
            """;
        IrProgram ir = compileToIr(src);
        Optional<IrInstruction> callInstructionOpt = ir.items().stream()
                .filter(IrInstruction.class::isInstance)
                .map(IrInstruction.class::cast)
                .filter(i -> "CALL".equalsIgnoreCase(i.opcode()))
                .findFirst();

        assertTrue(callInstructionOpt.isPresent(), "CALL instruction not found in IR");
        IrInstruction callInstruction = callInstructionOpt.get();

        assertEquals(1, callInstruction.operands().size());
        assertInstanceOf(IrLabelRef.class, callInstruction.operands().get(0));
        assertEquals("oldProc", ((IrLabelRef) callInstruction.operands().get(0)).labelName());

        assertEquals(1, callInstruction.refOperands().size());
        assertInstanceOf(IrReg.class, callInstruction.refOperands().get(0));
        assertEquals("%FPR0", ((IrReg) callInstruction.refOperands().get(0)).name());

        assertTrue(callInstruction.valOperands().isEmpty());
    }

    @Test
    @Tag("unit")
    void generatesIrForSimpleProgram() {
        String src = """
            .ORG 0|0
            L1:
              SETI %DR0 DATA:42
            """;

        IrProgram ir = compileToIr(src);

        List<IrItem> items = ir.items();
        assertTrue(items.size() >= 3, "Expected at least 3 IR items");

        assertTrue(items.get(0) instanceof IrDirective);
        IrDirective org = (IrDirective) items.get(0);
        assertEquals("org", org.name());

        assertTrue(items.get(1) instanceof IrLabelDef);
        IrLabelDef lbl = (IrLabelDef) items.get(1);
        assertEquals("L1", lbl.name());

        assertTrue(items.get(2) instanceof IrInstruction);
        IrInstruction seti = (IrInstruction) items.get(2);
        assertEquals("SETI", seti.opcode());
        assertEquals(2, seti.operands().size());
        assertTrue(seti.operands().get(0) instanceof IrReg);
        assertTrue(seti.operands().get(1) instanceof IrTypedImm);
    }

    @Test
    @Tag("unit")
    void endToEnd_sourceMapContentIsCorrect() throws org.evochora.compiler.api.CompilationException {
        String source = "SETI %DR0 DATA:42";
        org.evochora.compiler.Compiler compiler = new org.evochora.compiler.Compiler();
        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{10, 10}, true);

        org.evochora.compiler.api.ProgramArtifact artifact = compiler.compile(List.of(source), "EndToEndTest", envProps);

        assertThat(artifact.sourceMap()).isNotEmpty();

        org.evochora.compiler.api.SourceInfo infoForOpcode = artifact.sourceMap().get(0);
        assertThat(infoForOpcode).isNotNull();
        String lineContent = artifact.sources().get(infoForOpcode.fileName()).get(infoForOpcode.lineNumber() - 1);
        assertThat(lineContent.trim()).isEqualTo("SETI %DR0 DATA:42");

        org.evochora.compiler.api.SourceInfo infoForArg1 = artifact.sourceMap().get(1);
        assertThat(infoForArg1).isNotNull();
        String lineContent2 = artifact.sources().get(infoForArg1.fileName()).get(infoForArg1.lineNumber() - 1);
        assertThat(lineContent2.trim()).isEqualTo("SETI %DR0 DATA:42");
    }

    @Test
    @Tag("unit")
    void resolvesRefParametersWithinProcedure() {
        String src = """
            .PROC myProc REF rA VAL v1
                ADDR rA v1
                RET
            .ENDP
            """;
        IrProgram ir = compileToIr(src);
        
        // Find the ADDR instruction within the procedure
        Optional<IrInstruction> addrInstructionOpt = ir.items().stream()
                .filter(IrInstruction.class::isInstance)
                .map(IrInstruction.class::cast)
                .filter(i -> "ADDR".equalsIgnoreCase(i.opcode()))
                .findFirst();

        assertTrue(addrInstructionOpt.isPresent(), "ADDR instruction not found in IR");
        IrInstruction addrInstruction = addrInstructionOpt.get();

        // Check that parameters are resolved to %FPRx registers
        assertEquals(2, addrInstruction.operands().size());
        assertInstanceOf(IrReg.class, addrInstruction.operands().get(0));
        assertInstanceOf(IrReg.class, addrInstruction.operands().get(1));
        
        // REF parameter should be %FPR0, VAL parameter should be %FPR1
        assertEquals("%FPR0", ((IrReg) addrInstruction.operands().get(0)).name());
        assertEquals("%FPR1", ((IrReg) addrInstruction.operands().get(1)).name());
    }

    @Test
    @Tag("unit")
    void resolvesValParametersWithinProcedure() {
        String src = """
            .PROC myProc VAL v1 v2
                ADDR v1 v2
                RET
            .ENDP
            """;
        IrProgram ir = compileToIr(src);
        
        // Find the ADDR instruction within the procedure
        Optional<IrInstruction> addrInstructionOpt = ir.items().stream()
                .filter(IrInstruction.class::isInstance)
                .map(IrInstruction.class::cast)
                .filter(i -> "ADDR".equalsIgnoreCase(i.opcode()))
                .findFirst();

        assertTrue(addrInstructionOpt.isPresent(), "ADDR instruction not found in IR");
        IrInstruction addrInstruction = addrInstructionOpt.get();

        // Check that parameters are resolved to %FPRx registers
        assertEquals(2, addrInstruction.operands().size());
        assertInstanceOf(IrReg.class, addrInstruction.operands().get(0));
        assertInstanceOf(IrReg.class, addrInstruction.operands().get(1));
        
        // VAL parameters should be %FPR0 and %FPR1
        assertEquals("%FPR0", ((IrReg) addrInstruction.operands().get(0)).name());
        assertEquals("%FPR1", ((IrReg) addrInstruction.operands().get(1)).name());
    }

    @Test
    @Tag("unit")
    void resolvesNestedCallWithRefParameters() {
        String src = """
            .PROC outerProc REF rA VAL v1
                CALL innerProc REF rA VAL v1
                RET
            .ENDP
            
            .PROC innerProc REF rB VAL v2
                NOP
                RET
            .ENDP
            """;
        IrProgram ir = compileToIr(src);
        
        // Find the CALL instruction within the outer procedure
        Optional<IrInstruction> callInstructionOpt = ir.items().stream()
                .filter(IrInstruction.class::isInstance)
                .map(IrInstruction.class::cast)
                .filter(i -> "CALL".equalsIgnoreCase(i.opcode()))
                .findFirst();

        assertTrue(callInstructionOpt.isPresent(), "CALL instruction not found in IR");
        IrInstruction callInstruction = callInstructionOpt.get();

        // Check that the CALL has the correct operands
        assertEquals(1, callInstruction.operands().size());
        assertInstanceOf(IrLabelRef.class, callInstruction.operands().get(0));
        assertEquals("innerProc", ((IrLabelRef) callInstruction.operands().get(0)).labelName());

        // Check REF operands - should be resolved to %FPRx
        assertEquals(1, callInstruction.refOperands().size());
        assertInstanceOf(IrReg.class, callInstruction.refOperands().get(0));
        assertEquals("%FPR0", ((IrReg) callInstruction.refOperands().get(0)).name());

        // Check VAL operands - should be resolved to %FPRx
        assertEquals(1, callInstruction.valOperands().size());
        assertInstanceOf(IrReg.class, callInstruction.valOperands().get(0));
        assertEquals("%FPR1", ((IrReg) callInstruction.valOperands().get(0)).name());
    }
}