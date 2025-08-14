package org.evochora.compiler.frontend;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.irgen.IrConverterRegistry;
import org.evochora.compiler.frontend.irgen.IrGenerator;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.semantics.SemanticAnalyzer;
import org.evochora.compiler.ir.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-like test for the IR generator: runs Lexer -> Parser -> Semantics -> IR.
 */
public class IrGeneratorTest {

	@Test
	void generatesIrForSimpleProgram() {
		String src = ".ORG 0|0\n" +
				"L1: \n" +
				"  SETI %DR0 DATA:42\n";

		DiagnosticsEngine diagnostics = new DiagnosticsEngine();

		// 1) Lexing
		Lexer lexer = new Lexer(src, diagnostics);
		List<Token> tokens = lexer.scanTokens();
		assertFalse(diagnostics.hasErrors(), diagnostics.summary());

		// 2) Parsing
		Parser parser = new Parser(tokens, diagnostics);
		List<AstNode> ast = parser.parse();
		assertFalse(diagnostics.hasErrors(), diagnostics.summary());

		// 3) Semantics
		new SemanticAnalyzer(diagnostics).analyze(ast);
		assertFalse(diagnostics.hasErrors(), diagnostics.summary());

		// 4) IR-Gen
		IrConverterRegistry registry = IrConverterRegistry.initializeWithDefaults();
		IrGenerator irGen = new IrGenerator(diagnostics, registry);
		IrProgram ir = irGen.generate(ast, "TestProg");
		assertFalse(diagnostics.hasErrors(), diagnostics.summary());

		List<IrItem> items = ir.items();
		assertTrue(items.size() >= 3, "Expected at least 3 IR items");

		// .ORG directive
		assertTrue(items.get(0) instanceof IrDirective);
		IrDirective org = (IrDirective) items.get(0);
		assertEquals("org", org.name());

		// Label L1
		assertTrue(items.get(1) instanceof IrLabelDef);
		IrLabelDef lbl = (IrLabelDef) items.get(1);
		assertEquals("L1", lbl.name());

		// SETI %DR0 DATA:42
		assertTrue(items.get(2) instanceof IrInstruction);
		IrInstruction seti = (IrInstruction) items.get(2);
		assertEquals("SETI", seti.opcode());
		assertEquals(2, seti.operands().size());
		assertTrue(seti.operands().get(0) instanceof IrReg);
		assertTrue(seti.operands().get(1) instanceof IrTypedImm);

        // No jump instruction in this simplified example
	}
}


