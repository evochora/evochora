package org.evochora.compiler.features.proc;

import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parses the CALL instruction syntax, producing a {@link CallNode}.
 * Supports new syntax (REF/VAL/LREF/LVAL parameters) and legacy syntax (WITH parameters).
 */
public class CallStatementHandler implements IParserStatementHandler {

    private static final Set<String> PARAM_KEYWORDS = Set.of("REF", "VAL", "LREF", "LVAL");

    @Override
    public AstNode parse(ParsingContext context) {
        Token opcode = context.advance(); // consume CALL (OPCODE token)
        AstNode procName = context.expression();

        // Check for new syntax (REF/VAL/LREF/LVAL)
        if (context.check(TokenType.IDENTIFIER) && isParamKeyword(context.peek().text())) {
            List<AstNode> refArguments = new ArrayList<>();
            List<AstNode> valArguments = new ArrayList<>();
            List<AstNode> lrefArguments = new ArrayList<>();
            List<AstNode> lvalArguments = new ArrayList<>();

            while (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
                if (context.check(TokenType.IDENTIFIER) && "REF".equalsIgnoreCase(context.peek().text())) {
                    context.advance();
                    while (!context.isAtEnd() && !context.check(TokenType.NEWLINE) &&
                            !(context.check(TokenType.IDENTIFIER) && isParamKeyword(context.peek().text()))) {
                        refArguments.add(context.expression());
                    }
                } else if (context.check(TokenType.IDENTIFIER) && "VAL".equalsIgnoreCase(context.peek().text())) {
                    context.advance();
                    while (!context.isAtEnd() && !context.check(TokenType.NEWLINE) &&
                            !(context.check(TokenType.IDENTIFIER) && isParamKeyword(context.peek().text()))) {
                        valArguments.add(context.expression());
                    }
                } else if (context.check(TokenType.IDENTIFIER) && "LREF".equalsIgnoreCase(context.peek().text())) {
                    context.advance();
                    while (!context.isAtEnd() && !context.check(TokenType.NEWLINE) &&
                            !(context.check(TokenType.IDENTIFIER) && isParamKeyword(context.peek().text()))) {
                        lrefArguments.add(context.expression());
                    }
                } else if (context.check(TokenType.IDENTIFIER) && "LVAL".equalsIgnoreCase(context.peek().text())) {
                    context.advance();
                    while (!context.isAtEnd() && !context.check(TokenType.NEWLINE) &&
                            !(context.check(TokenType.IDENTIFIER) && isParamKeyword(context.peek().text()))) {
                        lvalArguments.add(context.expression());
                    }
                } else {
                    Token unexpected = context.advance();
                    context.getDiagnostics().reportError(
                            "Unexpected token '" + unexpected.text() + "' in CALL statement. Expected REF, VAL, LREF, LVAL, or newline.",
                            unexpected.fileName(), unexpected.line());
                    break;
                }
            }
            return new CallNode(procName, refArguments, valArguments, lrefArguments, lvalArguments, List.of(), opcode.toSourceInfo());
        } else {
            // Legacy syntax: CALL proc [WITH] arg1, arg2, ...
            List<AstNode> legacyArguments = new ArrayList<>();
            while (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
                legacyArguments.add(context.expression());
            }
            return new CallNode(procName, List.of(), List.of(), List.of(), List.of(), legacyArguments, opcode.toSourceInfo());
        }
    }

    private static boolean isParamKeyword(String text) {
        return PARAM_KEYWORDS.contains(text.toUpperCase());
    }
}
