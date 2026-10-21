package org.evochora.compiler.frontend;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class ExportTest {

    private static Token token(TokenType type, String text) {
        return new Token(type, text, null, new SourceInfo("main.evo", 1, 1, "", 0));
    }

    @Test
    void theWordIsRecognisedInAnyCaseAndNowhereElse() {
        assertThat(Export.isWord(token(TokenType.IDENTIFIER, "export"))).isTrue();
        assertThat(Export.isWord(token(TokenType.IDENTIFIER, "EXPORTS"))).isFalse();
        assertThat(Export.isWord(token(TokenType.STRING, "EXPORT"))).isFalse();
        assertThat(Export.isWord(null)).isFalse();
    }

    @Test
    void theMessageNamesTheKeywordOrTheWordBeforeTheDirective() {
        List<Token> exported = List.of(token(TokenType.IDENTIFIER, "EXPORT"), token(TokenType.DIRECTIVE, ".DEFINE"),
                token(TokenType.IDENTIFIER, "X"));
        assertThat(Export.statementStartMessage(DirectiveLine.of(exported, 1)))
                .isEqualTo("EXPORT is not supported before '.DEFINE'.");

        List<Token> afterWord = List.of(token(TokenType.OPCODE, "NOP"), token(TokenType.DIRECTIVE, ".DEFINE"),
                token(TokenType.IDENTIFIER, "X"));
        assertThat(Export.statementStartMessage(DirectiveLine.of(afterWord, 1)))
                .isEqualTo("Unexpected '.DEFINE' after 'NOP'.");
    }
}
