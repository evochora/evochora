package org.evochora.compiler.backend;

import org.evochora.compiler.backend.emit.EmissionRegistry;
import org.evochora.compiler.backend.emit.IEmissionRule;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.irgen.IrConverterRegistry;
import org.evochora.compiler.frontend.irgen.IrGenerator;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.semantics.SemanticAnalyzer;
import org.evochora.compiler.frontend.semantics.SymbolTable; // NEUER IMPORT
import org.evochora.compiler.ir.IrDirective;
import org.evochora.compiler.ir.IrInstruction;
import org.evochora.compiler.ir.IrItem;
import org.evochora.compiler.ir.IrProgram;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the emission phase of the compiler.
 * These tests verify that the entire pipeline from source code to final IR
 * correctly handles complex scenarios like function call marshalling.
 * These tests are tagged as integration tests because they involve multiple components
 * of the compiler pipeline.
 */
public class EmissionIntegrationTest {

    @BeforeAll
    static void setUp() {
        Instruction.init();
    }

    /**
     * An end-to-end test that verifies both caller and callee marshalling for a function call.
     * It compiles a source string containing a procedure and a call to it, then checks that
     * the correct PUSH/POP sequences are generated for argument passing and for the procedure's
     * prologue and epilogue.
     * This is an integration test as it covers the lexer, parser, semantic analyzer, IR generator,
     * and emission rule engine.
     *
     * TODO: Need to create a new test, that tests the same but with REF / VAL instead of WITH
     */
    @Test
    @Tag("integration")
    @Tag("legacy-with")
    void endToEnd_CallerAndCalleeMarshalling_legacy() {
        String src = String.join("\n",
                ".PROC INC WITH A",
                "  RET",
                ".ENDP",
                "L:",
                "  NOP",
                "CALL L WITH %DR1"
        );

        DiagnosticsEngine diags = new DiagnosticsEngine();
        Lexer lexer = new Lexer(src, diags);
        List<Token> tokens = lexer.scanTokens();
        // KORREKTUR: basePath hinzufügen
        Parser parser = new Parser(tokens, diags, Path.of(""));
        List<AstNode> ast = parser.parse();

        // KORREKTUR: Erstelle eine SymbolTable und übergib sie.
        SymbolTable symbolTable = new SymbolTable(diags);
        new SemanticAnalyzer(diags, symbolTable).analyze(ast);

        assertThat(diags.hasErrors()).as(diags.summary()).isFalse();

        IrConverterRegistry reg = IrConverterRegistry.initializeWithDefaults();
        IrGenerator gen = new IrGenerator(diags, reg);
        IrProgram ir = gen.generate(ast, "Test");
        List<IrItem> items = new ArrayList<>(ir.items());

        // Expect a core:call_with directive before CALL
        int callIdx = -1;
        for (int i = 0; i < items.size(); i++) if (items.get(i) instanceof IrInstruction ins && ins.opcode().equals("CALL")) { callIdx = i; break; }
        assertThat(callIdx).isGreaterThan(0);
        assertThat(items.get(callIdx - 1)).isInstanceOf(IrDirective.class);
        assertThat(((IrDirective) items.get(callIdx - 1)).name()).isEqualTo("call_with");

        // Apply emission rules
        EmissionRegistry eReg = EmissionRegistry.initializeWithDefaults();
        LinkingContext ctx = new LinkingContext();
        List<IrItem> rewritten = items;
        for (IEmissionRule r : eReg.rules()) rewritten = r.apply(rewritten, ctx);

        // Verify caller marshalling sequence around CALL
        int newCallIdx = -1;
        for (int i = 0; i < rewritten.size(); i++) if (rewritten.get(i) instanceof IrInstruction ins && ins.opcode().equals("CALL")) { newCallIdx = i; break; }
        assertThat(newCallIdx).isGreaterThan(0);
        assertThat(((IrInstruction) rewritten.get(newCallIdx - 1)).opcode()).isEqualTo("PUSH");
        assertThat(((IrInstruction) rewritten.get(newCallIdx + 1)).opcode()).isEqualTo("POP");

        // Verify callee prolog/epilog around procedure body
        int enterIdx = -1; int exitIdx = -1;
        for (int i = 0; i < rewritten.size(); i++) {
            if (rewritten.get(i) instanceof IrDirective d && d.name().equals("proc_enter")) enterIdx = i;
            if (rewritten.get(i) instanceof IrDirective d && d.name().equals("proc_exit")) exitIdx = i;
        }
        assertThat(enterIdx).isNotNegative();
        assertThat(exitIdx).isGreaterThan(enterIdx);
        // Prolog: POP nach proc_enter
        assertThat(((IrInstruction) rewritten.get(enterIdx + 1)).opcode()).isEqualTo("POP");
        // Epilog: PUSH vor RET, und RET vor proc_exit
        assertThat(((IrInstruction) rewritten.get(exitIdx - 2)).opcode()).isEqualTo("PUSH");
        assertThat(((IrInstruction) rewritten.get(exitIdx - 1)).opcode()).isEqualTo("RET");
    }
}