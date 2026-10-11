package org.evochora.compiler.features.importdir;

import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.OperandNode;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.frontend.parser.IParsingContext;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.diagnostics.ErrorRecoveryException;
import org.evochora.compiler.frontend.DirectiveLine;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Parses the {@code .REQUIRE} directive.
 *
 * <p>Syntax: {@code .REQUIRE MODULE "path" AS ALIAS} or
 * {@code [EXPORT] .REQUIRE VALUE NAME [DEFAULT]}
 *
 * <p>A module requirement declares a module the importer must supply through a
 * {@code USING MODULE} group of its {@code .IMPORT}; it produces a {@link RequireNode}. A value
 * requirement declares a constant the importer may supply through a {@code USING VALUE} group,
 * or the default holds; it produces a {@link ValueRequirementNode}. A value requirement takes
 * {@code EXPORT} as a {@code .CONST} does; a module requirement does not, because the importer
 * supplies the module and already knows it under its own name.
 *
 * <p>The main program, which nothing imports, takes its values from the configuration: in the
 * placement of the compilation root, a value the compilation's options give for the
 * requirement's name takes the default's place in the node, so the configuration is decisive.
 * It is read here, where it is known what an operand is, from the tokens the compiler lexed apart
 * from the token stream; nothing of the configuration enters the stream. A configured text that
 * is not exactly one operand is reported at the {@code .REQUIRE} line.
 *
 * <p>The directive stands alone on its line: a token of its file and line before it, or a token
 * after the alias or after the value and its default, is an error, and the rest of the line is
 * passed over.
 */
public class RequireDirectiveHandler implements IParserStatementHandler {

    private static final String AFTER_ALIAS = "the alias";
    private static final String AFTER_VALUE = "the value and its default";

    @Override
    public boolean supportsExport() { return true; }

    @Override
    public AstNode parse(IParsingContext context) {
        Token directive = context.peek();
        // An EXPORT before the directive belongs to it; the line rule passes over it.
        DirectiveLine line = context.currentLine(
                token -> token.type() == TokenType.IDENTIFIER && "EXPORT".equalsIgnoreCase(token.text()));
        if (line.before() != null) {
            String message = ".REQUIRE must be the first word on its line; found '" + line.before().text() + "' before it.";
            context.getDiagnostics().reportError(message, directive.source().fileName(), directive.source().lineNumber());
            throw new ErrorRecoveryException(message);
        }
        context.advance(); // consume .REQUIRE

        if (isWord(context, "MODULE")) {
            context.advance(); // consume MODULE
            if (context.isExported()) {
                context.getDiagnostics().reportError(
                        "EXPORT is not supported before '.REQUIRE MODULE': the importer supplies the module and already knows it under its own name.",
                        directive.source().fileName(), directive.source().lineNumber());
                skipLine(context);
                return null;
            }
            return parseModule(context, line);
        }
        if (isWord(context, "VALUE")) {
            context.advance(); // consume VALUE
            return parseValue(context, line, directive);
        }
        context.getDiagnostics().reportError(
                "Expected MODULE or VALUE after .REQUIRE; found '" + context.peek().text() + "'.",
                directive.source().fileName(), directive.source().lineNumber());
        skipLine(context);
        return null;
    }

    /**
     * Reads {@code "path" AS ALIAS} after {@code MODULE}.
     */
    private static AstNode parseModule(IParsingContext context, DirectiveLine line) {
        Token pathToken = context.consume(TokenType.STRING, "Expected a file path in quotes after .REQUIRE.");

        if (!isWord(context, "AS")) {
            context.getDiagnostics().reportError(
                    "Expected AS after .REQUIRE path.",
                    pathToken.source().fileName(), pathToken.source().lineNumber());
            return null;
        }
        context.advance(); // consume AS

        Token aliasToken = context.consume(TokenType.IDENTIFIER, "Expected an alias name after AS.");

        if (!standsAlone(context, line, AFTER_ALIAS)) {
            return null;
        }
        return new RequireNode((String) pathToken.value(), aliasToken.text(), aliasToken.source());
    }

    /**
     * Reads {@code NAME [DEFAULT]} after {@code VALUE}. The value slot of the node is an
     * identifier with the requirement's own name. In the root placement a configured value of
     * that name takes the default's place.
     */
    private static AstNode parseValue(IParsingContext context, DirectiveLine line, Token directive) {
        boolean exported = context.isExported();
        Token name = context.consume(TokenType.IDENTIFIER, "Expected a value name after VALUE.");
        OperandNode defaultValue = null;
        if (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
            defaultValue = context.expression();
            if (defaultValue == null) {
                return null;
            }
        }
        if (!standsAlone(context, line, AFTER_VALUE)) {
            return null;
        }
        List<Token> configured = configuredValue(context, name);
        if (configured != null) {
            Optional<OperandNode> operand = context.operandOf(configured);
            if (operand.isEmpty()) {
                context.getDiagnostics().reportError(
                        "Configured value '" + name.text() + "' is not an operand; a value is written as a .CONST takes it: a literal, a vector or a name.",
                        directive.source().fileName(), directive.source().lineNumber());
                return null;
            }
            defaultValue = operand.get();
        }
        return new ValueRequirementNode(name.text(), new IdentifierNode(name.text(), name.source()),
                defaultValue, exported, name.source());
    }

    /**
     * Returns the tokens of the configured value for a requirement of the main program.
     *
     * @return The tokens, or {@code null} when the requirement stands in a module or the
     *         configuration gives no value of its name.
     */
    private static List<Token> configuredValue(IParsingContext context, Token name) {
        String root = context.rootAliasChain();
        if (root == null || !root.equals(name.source().placement())) {
            return null;
        }
        return context.values().get(name.text().toUpperCase(Locale.ROOT));
    }

    /**
     * Reports a token or a {@code ;} after the directive's operands, and passes over the rest of
     * the line.
     *
     * @param after What the operands end with, for the message.
     * @return {@code true} when the directive stands alone on its line.
     */
    private static boolean standsAlone(IParsingContext context, DirectiveLine line, String after) {
        if (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
            Token extra = context.peek();
            context.getDiagnostics().reportError(
                    ".REQUIRE must stand alone on its line; found '" + extra.text() + "' after " + after + ".",
                    extra.source().fileName(), extra.source().lineNumber());
            skipLine(context);
            return false;
        }
        if (line.separator() != null) {
            context.getDiagnostics().reportError(
                    ".REQUIRE must stand alone on its line; found ';' after " + after + ".",
                    line.separator().source().fileName(), line.separator().source().lineNumber());
            return false;
        }
        return true;
    }

    private static boolean isWord(IParsingContext context, String word) {
        return !context.isAtEnd() && context.check(TokenType.IDENTIFIER) && word.equalsIgnoreCase(context.peek().text());
    }

    private static void skipLine(IParsingContext context) {
        while (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
            context.advance();
        }
    }
}
