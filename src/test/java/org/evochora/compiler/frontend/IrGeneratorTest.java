/*
 * SPDX-FileCopyrightText: 2024-2024 EvoChora contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evochora.compiler.frontend;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.irgen.DefaultAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrConverterRegistry;
import org.evochora.compiler.frontend.irgen.IrGenerator;
import org.evochora.compiler.features.define.DefineNodeConverter;
import org.evochora.compiler.features.dir.DirNodeConverter;
import org.evochora.compiler.features.importdir.ImportNodeConverter;
import org.evochora.compiler.features.instruction.InstructionNodeConverter;
import org.evochora.compiler.features.label.LabelNodeConverter;
import org.evochora.compiler.features.org.OrgNodeConverter;
import org.evochora.compiler.features.place.PlaceNodeConverter;
import org.evochora.compiler.features.proc.ProcedureNodeConverter;
import org.evochora.compiler.features.reg.RegNodeConverter;
import org.evochora.compiler.features.require.RequireNodeConverter;
import org.evochora.compiler.features.ctx.PopCtxNode;
import org.evochora.compiler.features.ctx.PopCtxNodeConverter;
import org.evochora.compiler.features.ctx.PushCtxNode;
import org.evochora.compiler.features.ctx.PushCtxNodeConverter;
import org.evochora.compiler.features.define.DefineNode;
import org.evochora.compiler.features.dir.DirNode;
import org.evochora.compiler.features.importdir.ImportNode;
import org.evochora.compiler.features.label.LabelNode;
import org.evochora.compiler.features.org.OrgNode;
import org.evochora.compiler.features.place.PlaceNode;
import org.evochora.compiler.features.proc.ProcedureNode;
import org.evochora.compiler.features.reg.RegNode;
import org.evochora.compiler.features.require.RequireNode;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParserStatementRegistry;
import org.evochora.compiler.features.ctx.PopCtxDirectiveHandler;
import org.evochora.compiler.features.ctx.PushCtxDirectiveHandler;
import org.evochora.compiler.features.define.DefineDirectiveHandler;
import org.evochora.compiler.features.dir.DirDirectiveHandler;
import org.evochora.compiler.features.importdir.ImportDirectiveHandler;
import org.evochora.compiler.features.org.OrgDirectiveHandler;
import org.evochora.compiler.features.place.PlaceDirectiveHandler;
import org.evochora.compiler.features.proc.ProcDirectiveHandler;
import org.evochora.compiler.features.reg.RegDirectiveHandler;
import org.evochora.compiler.features.require.RequireDirectiveHandler;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.InstructionNode;
import org.evochora.compiler.TestRegistries;
import org.evochora.compiler.frontend.semantics.SemanticAnalyzer;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.compiler.features.proc.IrCallInstruction;
import org.evochora.compiler.model.ir.*;
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

        Parser parser = new Parser(tokens, diagnostics, allHandlers());
        List<AstNode> ast = parser.parse();
        if (diagnostics.hasErrors()) {
            fail("Parser errors: " + diagnostics.summary());
        }

        String rootAliasChain = "";
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        symbolTable.registerModule(rootAliasChain, "<memory>");
        symbolTable.setCurrentModule(rootAliasChain);
        new SemanticAnalyzer(diagnostics, symbolTable, null, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry()).analyze(ast);
        if (diagnostics.hasErrors()) {
            fail("Semantic analysis errors: " + diagnostics.summary());
        }

        IrConverterRegistry registry = allConverters();
        IrGenerator irGen = new IrGenerator(diagnostics, registry);
        IrProgram ir = irGen.generate(ast, "TestProg", rootAliasChain);
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
        Optional<IrCallInstruction> callInstructionOpt = ir.items().stream()
                .filter(IrCallInstruction.class::isInstance)
                .map(IrCallInstruction.class::cast)
                .findFirst();

        assertTrue(callInstructionOpt.isPresent(), "CALL instruction not found in IR");
        IrCallInstruction callInstruction = callInstructionOpt.get();

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
        Optional<IrCallInstruction> callInstructionOpt = ir.items().stream()
                .filter(IrCallInstruction.class::isInstance)
                .map(IrCallInstruction.class::cast)
                .findFirst();

        assertTrue(callInstructionOpt.isPresent(), "CALL instruction not found in IR");
        IrCallInstruction callInstruction = callInstructionOpt.get();

        assertEquals(1, callInstruction.operands().size());
        assertInstanceOf(IrLabelRef.class, callInstruction.operands().get(0));
        assertEquals("oldProc", ((IrLabelRef) callInstruction.operands().get(0)).labelName());

        assertEquals(1, callInstruction.refOperands().size());
        assertInstanceOf(IrReg.class, callInstruction.refOperands().get(0));
        assertEquals("%FDR0", ((IrReg) callInstruction.refOperands().get(0)).name());

        assertTrue(callInstruction.valOperands().isEmpty());
    }

    @Test
    @Tag("unit")
    void generatesIrForSimpleProgram() {
        String src = """
            .ORG 0|0
            .LABEL L1
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
        // Single-file mode: rootAliasChain is empty, labels are unqualified
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

        // Check that parameters are resolved to %FDRx registers
        assertEquals(2, addrInstruction.operands().size());
        assertInstanceOf(IrReg.class, addrInstruction.operands().get(0));
        assertInstanceOf(IrReg.class, addrInstruction.operands().get(1));
        
        // REF parameter should be %FDR0, VAL parameter should be %FDR1
        assertEquals("%FDR0", ((IrReg) addrInstruction.operands().get(0)).name());
        assertEquals("%FDR1", ((IrReg) addrInstruction.operands().get(1)).name());
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

        // Check that parameters are resolved to %FDRx registers
        assertEquals(2, addrInstruction.operands().size());
        assertInstanceOf(IrReg.class, addrInstruction.operands().get(0));
        assertInstanceOf(IrReg.class, addrInstruction.operands().get(1));
        
        // VAL parameters should be %FDR0 and %FDR1
        assertEquals("%FDR0", ((IrReg) addrInstruction.operands().get(0)).name());
        assertEquals("%FDR1", ((IrReg) addrInstruction.operands().get(1)).name());
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
        Optional<IrCallInstruction> callInstructionOpt = ir.items().stream()
                .filter(IrCallInstruction.class::isInstance)
                .map(IrCallInstruction.class::cast)
                .findFirst();

        assertTrue(callInstructionOpt.isPresent(), "CALL instruction not found in IR");
        IrCallInstruction callInstruction = callInstructionOpt.get();

        // Check that the CALL has the correct operands
        assertEquals(1, callInstruction.operands().size());
        assertInstanceOf(IrLabelRef.class, callInstruction.operands().get(0));
        assertEquals("innerProc", ((IrLabelRef) callInstruction.operands().get(0)).labelName());

        // Check REF operands - should be resolved to %FDRx
        assertEquals(1, callInstruction.refOperands().size());
        assertInstanceOf(IrReg.class, callInstruction.refOperands().get(0));
        assertEquals("%FDR0", ((IrReg) callInstruction.refOperands().get(0)).name());

        // Check VAL operands - should be resolved to %FDRx
        assertEquals(1, callInstruction.valOperands().size());
        assertInstanceOf(IrReg.class, callInstruction.valOperands().get(0));
        assertEquals("%FDR1", ((IrReg) callInstruction.valOperands().get(0)).name());
    }

    private static ParserStatementRegistry allHandlers() {
        ParserStatementRegistry reg = new ParserStatementRegistry();
        reg.register(".DEFINE", new DefineDirectiveHandler());
        reg.register(".REG", new RegDirectiveHandler());
        reg.register(".PROC", new ProcDirectiveHandler());
        reg.register(".ORG", new OrgDirectiveHandler());
        reg.register(".DIR", new DirDirectiveHandler());
        reg.register(".PLACE", new PlaceDirectiveHandler());
        reg.register(".IMPORT", new ImportDirectiveHandler());
        reg.register(".REQUIRE", new RequireDirectiveHandler());
        reg.register(".PUSH_CTX", new PushCtxDirectiveHandler());
        reg.register(".POP_CTX", new PopCtxDirectiveHandler());
        reg.register(".LABEL", new org.evochora.compiler.features.label.LabelDirectiveHandler());
        reg.register("CALL", new org.evochora.compiler.features.proc.CallStatementHandler());
        reg.registerDefault(new org.evochora.compiler.features.instruction.InstructionParsingHandler());
        return reg;
    }

    private static IrConverterRegistry allConverters() {
        IrConverterRegistry reg = IrConverterRegistry.initialize(new DefaultAstNodeToIrConverter());
        reg.register(InstructionNode.class, new InstructionNodeConverter());
        reg.register(LabelNode.class, new LabelNodeConverter());
        reg.register(OrgNode.class, new OrgNodeConverter());
        reg.register(DirNode.class, new DirNodeConverter());
        reg.register(PlaceNode.class, new PlaceNodeConverter());
        reg.register(ProcedureNode.class, new ProcedureNodeConverter());
        reg.register(DefineNode.class, new DefineNodeConverter());
        reg.register(ImportNode.class, new ImportNodeConverter());
        reg.register(RequireNode.class, new RequireNodeConverter());
        reg.register(RegNode.class, new RegNodeConverter());
        reg.register(org.evochora.compiler.features.proc.CallNode.class, new org.evochora.compiler.features.proc.CallNodeConverter());
        reg.register(PushCtxNode.class, new PushCtxNodeConverter());
        reg.register(PopCtxNode.class, new PopCtxNodeConverter());
        return reg;
    }
}