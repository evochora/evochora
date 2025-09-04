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
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;

public class IrGeneratorTest {

    @Test
    @Tag("unit")
    void generatesIrForSimpleProgram() {
        String src = ".ORG 0|0\n" +
                "L1: \n" +
                "  SETI %DR0 DATA:42\n";

        DiagnosticsEngine diagnostics = new DiagnosticsEngine();

        Lexer lexer = new Lexer(src, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        assertFalse(diagnostics.hasErrors(), diagnostics.summary());

        Parser parser = new Parser(tokens, diagnostics, Path.of(""));
        List<AstNode> ast = parser.parse();
        assertFalse(diagnostics.hasErrors(), diagnostics.summary());

        SymbolTable symbolTable = new SymbolTable(diagnostics);
        new SemanticAnalyzer(diagnostics, symbolTable).analyze(ast);
        assertFalse(diagnostics.hasErrors(), diagnostics.summary());

        IrConverterRegistry registry = IrConverterRegistry.initializeWithDefaults();
        IrGenerator irGen = new IrGenerator(diagnostics, registry);
        IrProgram ir = irGen.generate(ast, "TestProg");
        assertFalse(diagnostics.hasErrors(), diagnostics.summary());

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
}