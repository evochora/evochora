package org.evochora.compiler.frontend;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DirectiveLine}, the rule that a directive stands alone on its physical
 * line, read from the positions of the tokens.
 */
@Tag("unit")
class DirectiveLineTest {

    private static final String FILE = "main.evo";

    @Test
    void aDirectiveAloneOnItsLineHasItsOperandsAndEndsAtTheLineBreak() {
        List<Token> tokens = List.of(
                newline(1),
                word(".D", 2), word("A", 2), word("1", 2), newline(2),
                word("NOP", 3));

        DirectiveLine line = DirectiveLine.of(tokens, 1);

        assertThat(line.standsAlone()).isTrue();
        assertThat(line.operands()).extracting(Token::text).containsExactly("A", "1");
        assertThat(line.end()).isEqualTo(4);
        assertThat(line.next()).isEqualTo(5);
    }

    @Test
    void aWordOfTheSameLineBeforeTheDirectiveBreaksTheRule() {
        List<Token> tokens = List.of(word("L", 1), word(".D", 1), newline(1));

        DirectiveLine line = DirectiveLine.of(tokens, 1);

        assertThat(line.standsAlone()).isFalse();
        assertThat(line.before().text()).isEqualTo("L");
        assertThat(line.separator()).isNull();
    }

    @Test
    void theNearestWordBeforeIsFoundBehindAStatementEnd() {
        List<Token> tokens = List.of(word("NOP", 1), semicolon(1), word(".D", 1), newline(1));

        assertThat(DirectiveLine.of(tokens, 2).before().text()).isEqualTo("NOP");
    }

    @Test
    void aTokenTheCallerPassesOverDoesNotBreakTheRule() {
        List<Token> tokens = List.of(word("EXPORT", 1), word(".D", 1), newline(1));

        assertThat(DirectiveLine.of(tokens, 1, token -> token.text().equals("EXPORT")).standsAlone()).isTrue();
        assertThat(DirectiveLine.of(tokens, 1).before().text()).isEqualTo("EXPORT");
    }

    @Test
    void aSemicolonOnTheSameLineEndsTheOperandsAndBreaksTheRule() {
        List<Token> tokens = List.of(word(".D", 1), word("A", 1), semicolon(1), word("NOP", 1), newline(1));

        DirectiveLine line = DirectiveLine.of(tokens, 0);

        assertThat(line.standsAlone()).isFalse();
        assertThat(line.before()).isNull();
        assertThat(line.separator()).isSameAs(tokens.get(2));
        assertThat(line.operands()).extracting(Token::text).containsExactly("A");
        assertThat(line.next()).isEqualTo(3);
    }

    @Test
    void tokensOfAnotherFileOrLineDoNotCount() {
        Token marker = new Token(TokenType.DIRECTIVE, ".PUSH_CTX", null, new SourceInfo("other.evo", 1, 0, "", 0));
        Token separator = new Token(TokenType.NEWLINE, ";", null, new SourceInfo(FILE, 9, 1, "", 0));
        List<Token> tokens = List.of(marker, word(".D", 1), separator);

        DirectiveLine line = DirectiveLine.of(tokens, 1);

        assertThat(line.standsAlone()).isTrue();
        assertThat(line.end()).isEqualTo(2);
    }

    @Test
    void aDirectiveAtTheEndOfTheStreamEndsThere() {
        List<Token> tokens = List.of(word(".D", 1), word("A", 1));

        DirectiveLine line = DirectiveLine.of(tokens, 0);

        assertThat(line.standsAlone()).isTrue();
        assertThat(line.end()).isEqualTo(2);
        assertThat(line.next()).isEqualTo(2);
    }

    private static Token word(String text, int line) {
        return new Token(TokenType.IDENTIFIER, text, null, new SourceInfo(FILE, line, 1, "", 0));
    }

    private static Token newline(int line) {
        return new Token(TokenType.NEWLINE, "\n", null, new SourceInfo(FILE, line, 80, "", 0));
    }

    private static Token semicolon(int line) {
        return new Token(TokenType.NEWLINE, ";", null, new SourceInfo(FILE, line, 40, "", 0));
    }
}
