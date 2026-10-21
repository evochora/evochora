package org.evochora.compiler.frontend;

import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

/**
 * The {@code EXPORT} keyword: written directly before a statement, it marks the statement for
 * export. The parser reads it before the statement; a directive the preprocessor or the lexer
 * phase reads itself passes it over when it takes the keyword, and reports it otherwise. Every
 * phase recognises the word and words its rejection here, in one place.
 */
public final class Export {

    private Export() {
    }

    /**
     * Whether a token is the keyword.
     *
     * @param token The token; {@code null} is no keyword.
     * @return {@code true} for the word, in any case.
     */
    public static boolean isWord(Token token) {
        return token != null && token.type() == TokenType.IDENTIFIER && "EXPORT".equalsIgnoreCase(token.text());
    }

    /**
     * The message for a word that does not take the keyword before it.
     *
     * @param word The word after the keyword.
     * @return The message.
     */
    public static String notSupportedBefore(Token word) {
        return "EXPORT is not supported before '" + word.text() + "'.";
    }

    /**
     * The message for a directive that does not begin a statement: the rejection of the keyword
     * when the keyword stands before the directive, and otherwise the word that stands there.
     *
     * @param line The directive's line, whose {@code startsStatement} is {@code false}.
     * @return The message.
     */
    public static String statementStartMessage(DirectiveLine line) {
        Token before = line.before();
        return isWord(before)
                ? notSupportedBefore(line.word())
                : "Unexpected '" + line.word().text() + "' after '" + before.text() + "'.";
    }
}
