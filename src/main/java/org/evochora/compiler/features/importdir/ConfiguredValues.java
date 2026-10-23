package org.evochora.compiler.features.importdir;

import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.List;

/**
 * The words of the configured values of the main program, which stand in no source file: the
 * text of a configured value as the configuration gave it, and the message of an error in one,
 * which names the value and its text and is reported against the configuration, without a file
 * or line.
 */
final class ConfiguredValues {

    private ConfiguredValues() {
    }

    /**
     * Writes the tokens of a configured value back to its text: the tokens stand on one line, so
     * their columns give the spaces between them.
     *
     * @param tokens The tokens the lexer made of the text, the end of file among them.
     * @return The text from its first token to its last; {@code null} when there are no tokens,
     *         because the lexer rejected the text.
     */
    static String textOf(List<Token> tokens) {
        List<Token> written = tokens.stream().filter(token -> token.type() != TokenType.END_OF_FILE).toList();
        if (written.isEmpty()) {
            return null;
        }
        int first = written.getFirst().source().columnNumber();
        StringBuilder text = new StringBuilder();
        for (Token token : written) {
            while (text.length() < token.source().columnNumber() - first) {
                text.append(' ');
            }
            text.append(token.text());
        }
        return text.toString();
    }

    /**
     * Builds the message of an error in a configured value.
     *
     * @param name   The name of the value.
     * @param text   The value as the configuration gave it; {@code null} when it is not known.
     * @param reason The error, one sentence; it follows the colon in lower case.
     * @return The message, e.g. {@code Configured value 'WIDTH' = (A/0): division by zero.}
     */
    static String message(String name, String text, String reason) {
        String lowered = reason.isEmpty() ? reason : Character.toLowerCase(reason.charAt(0)) + reason.substring(1);
        return "Configured value '" + name + "'" + (text != null ? " = " + text : "") + ": " + lowered;
    }
}
