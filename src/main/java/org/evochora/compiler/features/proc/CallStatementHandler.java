package org.evochora.compiler.features.proc;

import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the CALL instruction syntax, producing a {@link CallNode}.
 * Supports both new syntax (REF/VAL parameters) and legacy syntax (WITH parameters).
 */
public class CallStatementHandler implements IParserStatementHandler {

    @Override
    public AstNode parse(ParsingContext context) {
        Token opcode = context.advance(); // consume CALL (OPCODE token)
        AstNode procName = context.expression();

        // Check for new syntax (REF/VAL)
        if (context.check(TokenType.IDENTIFIER) &&
                ("REF".equalsIgnoreCase(context.peek().text()) || "VAL".equalsIgnoreCase(context.peek().text()))) {
            List<AstNode> refArguments = new ArrayList<>();
            List<AstNode> valArguments = new ArrayList<>();
            boolean refParsed = false;
            boolean valParsed = false;

            while (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
                if (!refParsed && context.check(TokenType.IDENTIFIER) && "REF".equalsIgnoreCase(context.peek().text())) {
                    context.advance(); // Consume REF
                    refParsed = true;
                    while (!context.isAtEnd() && !context.check(TokenType.NEWLINE) &&
                            !(context.check(TokenType.IDENTIFIER) && "VAL".equalsIgnoreCase(context.peek().text()))) {
                        refArguments.add(context.expression());
                    }
                } else if (!valParsed && context.check(TokenType.IDENTIFIER) && "VAL".equalsIgnoreCase(context.peek().text())) {
                    context.advance(); // Consume VAL
                    valParsed = true;
                    while (!context.isAtEnd() && !context.check(TokenType.NEWLINE) &&
                            !(context.check(TokenType.IDENTIFIER) && "REF".equalsIgnoreCase(context.peek().text()))) {
                        valArguments.add(context.expression());
                    }
                } else {
                    Token unexpected = context.advance();
                    context.getDiagnostics().reportError(
                            "Unexpected token '" + unexpected.text() + "' in CALL statement. Expected REF, VAL, or newline.",
                            unexpected.fileName(), unexpected.line());
                    break;
                }
            }
            return new CallNode(procName, refArguments, valArguments, List.of(), opcode.toSourceInfo());
        } else {
            // Legacy syntax: CALL proc [WITH] arg1, arg2, ...
            List<AstNode> legacyArguments = new ArrayList<>();
            while (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
                legacyArguments.add(context.expression());
            }
            return new CallNode(procName, List.of(), List.of(), legacyArguments, opcode.toSourceInfo());
        }
    }
}
