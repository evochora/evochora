package org.evochora.compiler.features.proc;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.features.instruction.InstructionParsingHandler;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParserStatementRegistry;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.token.Token;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CallStatementHandler}.
 */
@Tag("unit")
class CallStatementHandlerTest {

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    private CallNode parseCall(String source) {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, "test.s");
        List<Token> tokens = lexer.scanTokens();
        ParserStatementRegistry reg = new ParserStatementRegistry();
        reg.register("CALL", new CallStatementHandler());
        reg.registerDefault(new InstructionParsingHandler());
        Parser parser = new Parser(tokens, diagnostics, reg);
        List<AstNode> ast = parser.parse();
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        assertThat(ast.get(0)).isInstanceOf(CallNode.class);
        return (CallNode) ast.get(0);
    }

    @Test
    void newSyntax_refAndVal() {
        CallNode node = parseCall("CALL myProc REF %DR0 VAL 42");

        assertThat(node.procedureName()).isInstanceOf(IdentifierNode.class);
        assertThat(((IdentifierNode) node.procedureName()).text()).isEqualTo("myProc");
        assertThat(node.refArguments()).hasSize(1);
        assertThat(node.valArguments()).hasSize(1);
    }

    @Test
    void newSyntax_refOnly() {
        CallNode node = parseCall("CALL myProc REF %DR0");

        assertThat(node.refArguments()).hasSize(1);
        assertThat(node.valArguments()).isEmpty();
    }

    @Test
    void newSyntax_valOnly() {
        CallNode node = parseCall("CALL myProc VAL 42");

        assertThat(node.valArguments()).hasSize(1);
        assertThat(node.refArguments()).isEmpty();
    }

    @Test
    void legacySyntax_withArgs() {
        CallNode node = parseCall("CALL myProc %DR0");

        assertThat(node.legacyArguments()).hasSize(1);
    }

    @Test
    void legacySyntax_noArgs() {
        CallNode node = parseCall("CALL myProc");

        assertThat(node.legacyArguments()).isEmpty();
    }

    @Test
    void sourceInfoPreserved() {
        CallNode node = parseCall("CALL myProc");

        assertThat(node.sourceInfo()).isNotNull();
        assertThat(node.sourceInfo().fileName()).isEqualTo("test.s");
        assertThat(node.sourceInfo().lineNumber()).isEqualTo(1);
    }
}
