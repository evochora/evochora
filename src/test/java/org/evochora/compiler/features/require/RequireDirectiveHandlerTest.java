package org.evochora.compiler.features.require;

import org.evochora.compiler.TestLexers;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParserStatementRegistry;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.token.Token;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RequireDirectiveHandler}.
 */
@Tag("unit")
class RequireDirectiveHandlerTest {

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void aRequireAsTheFirstTokenOfTheStreamIsAccepted() {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<Token> tokens = new Lexer(".REQUIRE \"d.evo\" AS D\n", diagnostics, "lib.evo", TestLexers.symbols())
                .scanTokens();
        ParserStatementRegistry registry = new ParserStatementRegistry();
        registry.register(".REQUIRE", new RequireDirectiveHandler());

        List<AstNode> ast = new Parser(tokens, diagnostics, registry).parse();

        assertThat(diagnostics.hasErrors()).as(diagnostics.summary()).isFalse();
        assertThat(ast).singleElement().isInstanceOf(RequireNode.class);
    }
}
