package org.evochora.compiler.features.importdir;

import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.frontend.parser.IParsingContext;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.OperandNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parses the {@code .IMPORT} directive.
 *
 * <p>Syntax: {@code [EXPORT] .IMPORT "path" AS ALIAS [USING {MODULE source AS target ... | VALUE name value ...} ...]}
 *
 * <p>This handler produces an {@link ImportNode} AST node. The actual module loading
 * is handled by the Lexer phase (ImportDependencyScanHandler) and preprocessing (ImportSourceHandler).
 *
 * <p>{@code USING} stands once on an import and opens the clause. Each kind word in it opens a
 * list, and the groups that follow it, up to the next kind word, belong to that list; a kind
 * word may repeat. After {@code MODULE}, a group is {@code source AS target}; after
 * {@code VALUE}, a group is {@code name value}, the value read as an operand.
 *
 * <p>The alias and its clauses end the statement: a token after them that is no kind word is an
 * error, and the rest of the statement is passed over.
 */
public class ImportDirectiveHandler implements IParserStatementHandler {

    private static final Set<String> KIND_WORDS = Set.of("MODULE", "VALUE");

    @Override
    public boolean supportsExport() { return true; }

    @Override
    public AstNode parse(IParsingContext context) {
        // The dependency scan reads path and alias only, and loads the module from them; the
        // clauses after the alias are read here alone.
        boolean exported = context.isExported();
        context.advance(); // consume .IMPORT

        Token pathToken = context.consume(TokenType.STRING, "Expected a file path in quotes after .IMPORT.");

        // Consume AS keyword
        if (!isWord(context, "AS")) {
            context.getDiagnostics().reportError(
                    "Expected AS after .IMPORT path.",
                    pathToken.source().fileName(), pathToken.source().lineNumber());
            return null;
        }
        context.advance(); // consume AS

        Token aliasToken = context.consume(TokenType.IDENTIFIER, "Expected an alias name after AS.");

        List<ImportNode.UsingClause> usings = new ArrayList<>();
        List<ImportNode.ValueBinding> values = new ArrayList<>();
        if (isWord(context, "USING")) {
            context.advance(); // consume USING
            if (!isKindWord(context)) {
                Token found = context.peek();
                context.getDiagnostics().reportError(
                        "Expected MODULE or VALUE after USING; found '" + found.text() + "'.",
                        found.source().fileName(), found.source().lineNumber());
                context.skipStatement();
                return null;
            }
            // Each kind word opens a list; the groups that follow it, up to the next kind word,
            // belong to that list.
            while (isKindWord(context)) {
                boolean valueList = isWord(context, "VALUE");
                Token kindWord = context.advance(); // consume the kind word
                if (!context.check(TokenType.IDENTIFIER) || isKindWord(context) || isWord(context, "USING")) {
                    context.getDiagnostics().reportError(
                            valueList ? "Expected <name> <value> after VALUE." : "Expected <source> AS <target> after MODULE.",
                            kindWord.source().fileName(), kindWord.source().lineNumber());
                    context.skipStatement();
                    return null;
                }
                while (context.check(TokenType.IDENTIFIER) && !isKindWord(context) && !isWord(context, "USING")) {
                    if (valueList) {
                        if (!readValueGroup(context, values)) {
                            context.skipStatement();
                            return null;
                        }
                    } else if (!readModuleGroup(context, usings)) {
                        context.skipStatement();
                        return null;
                    }
                }
                if (isWord(context, "USING")) {
                    Token second = context.peek();
                    context.getDiagnostics().reportError(
                            "USING stands once on an import; a further list begins with its kind word.",
                            second.source().fileName(), second.source().lineNumber());
                    context.skipStatement();
                    return null;
                }
            }
        }

        if (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
            Token extra = context.peek();
            context.getDiagnostics().reportError(
                    "Unexpected '" + extra.text() + "' after the alias and its USING clauses of .IMPORT.",
                    extra.source().fileName(), extra.source().lineNumber());
            context.skipStatement();
            return null;
        }

        return new ImportNode(
                (String) pathToken.value(), aliasToken.text(), usings, values, exported,
                aliasToken.source());
    }

    /**
     * Reads one group {@code name value} of the {@code VALUE} list and adds it to the bindings,
     * unless its name is bound already.
     *
     * @return {@code false} when the group lacks its value or the value cannot be read, so that
     *         the clause cannot be read further.
     */
    private static boolean readValueGroup(IParsingContext context, List<ImportNode.ValueBinding> values) {
        Token name = context.advance(); // the loop stands on an identifier
        if (context.isAtEnd() || context.check(TokenType.NEWLINE) || isKindWord(context)) {
            context.getDiagnostics().reportError(
                    "Expected a value after VALUE " + name.text() + ".",
                    name.source().fileName(), name.source().lineNumber());
            return false;
        }
        OperandNode value = context.expression();
        if (value == null) {
            return false;
        }

        boolean bound = values.stream().anyMatch(v -> v.name().equalsIgnoreCase(name.text()));
        if (bound) {
            context.getDiagnostics().reportError(
                    "Cannot bind '" + name.text() + "' twice in the USING clauses of one import.",
                    name.source().fileName(), name.source().lineNumber());
            return true;
        }
        values.add(new ImportNode.ValueBinding(name.text(), value, name.source()));
        return true;
    }

    /**
     * Reads one group {@code source AS target} of the {@code MODULE} list and adds it to the
     * clauses, unless its target is bound already.
     *
     * @return {@code false} when the group lacks its {@code AS}, so that the clause cannot be
     *         read further.
     */
    private static boolean readModuleGroup(IParsingContext context, List<ImportNode.UsingClause> usings) {
        Token sourceAlias = context.consume(TokenType.IDENTIFIER, "Expected a source alias after USING.");

        if (!isWord(context, "AS")) {
            context.getDiagnostics().reportError(
                    "Expected AS after USING source alias.",
                    sourceAlias.source().fileName(), sourceAlias.source().lineNumber());
            return false;
        }
        context.advance(); // consume AS

        Token targetAlias = context.consume(TokenType.IDENTIFIER, "Expected a target alias after AS.");

        boolean bound = usings.stream().anyMatch(u -> u.targetAlias().equalsIgnoreCase(targetAlias.text()));
        if (bound) {
            context.getDiagnostics().reportError(
                    "Cannot bind '" + targetAlias.text() + "' twice in the USING clauses of one import.",
                    targetAlias.source().fileName(), targetAlias.source().lineNumber());
            return true;
        }
        usings.add(new ImportNode.UsingClause(
                sourceAlias.text(), targetAlias.text(),
                sourceAlias.source(), targetAlias.source()));
        return true;
    }

    private static boolean isWord(IParsingContext context, String word) {
        return !context.isAtEnd() && context.check(TokenType.IDENTIFIER) && word.equalsIgnoreCase(context.peek().text());
    }

    private static boolean isKindWord(IParsingContext context) {
        return !context.isAtEnd() && context.check(TokenType.IDENTIFIER)
                && KIND_WORDS.contains(context.peek().text().toUpperCase());
    }
}
