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
import org.evochora.compiler.ir.IrDirective;
import org.evochora.compiler.ir.IrInstruction;
import org.evochora.compiler.ir.IrItem;
import org.evochora.compiler.ir.IrProgram;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class EmissionIntegrationTest {

    @Test
    void endToEnd_CallerAndCalleeMarshalling() {
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
        Parser parser = new Parser(tokens, diags);
        List<AstNode> ast = parser.parse();
        new SemanticAnalyzer(diags).analyze(ast);
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