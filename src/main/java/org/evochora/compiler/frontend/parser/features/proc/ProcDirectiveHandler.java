package org.evochora.compiler.frontend.parser.features.proc;

import org.evochora.compiler.frontend.parser.IParserDirectiveHandler;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParsingContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles the parsing of the <code>.PROC</code> and <code>.ENDP</code> directives, which define a procedure block.
 * Procedures can be exported and can have parameters.
 */
public class ProcDirectiveHandler implements IParserDirectiveHandler {

    /**
     * Parses a procedure definition, including its body, until an <code>.ENDP</code> directive is found.
     * The syntax is <code>.PROC &lt;name&gt; [EXPORT] [WITH &lt;param1&gt; &lt;param2&gt; ...] ... .ENDP</code>.
     * @param context The parsing context.
     * @return A {@link ProcedureNode} representing the parsed procedure.
     */
    @Override
    public AstNode parse(ParsingContext context) {
        Parser parser = (Parser) context;
        context.advance(); // consume .PROC

        Token procName = context.consume(TokenType.IDENTIFIER, "Expected procedure name after .PROC.");
        boolean exported = false;
        List<Token> parameters = new ArrayList<>();
        List<Token> refParameters = new ArrayList<>();
        List<Token> valParameters = new ArrayList<>();
        // Flexible loop to parse optional keywords like EXPORT, WITH, REF, and VAL
        while (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
            if (context.check(TokenType.IDENTIFIER)) {
                String keyword = context.peek().text();
                if ("EXPORT".equalsIgnoreCase(keyword)) {
                    context.advance();
                    exported = true;
                } else if ("WITH".equalsIgnoreCase(keyword)) {
                    context.advance();
                    // After WITH, only parameters follow until newline
                    while (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
                        parameters.add(context.consume(TokenType.IDENTIFIER, "Expected a formal parameter name after WITH."));
                    }
                    break; // No other keywords should follow WITH on the same line
                } else if ("REF".equalsIgnoreCase(keyword)) {
                    context.advance();
                    while (!context.isAtEnd() && context.check(TokenType.IDENTIFIER) && !"VAL".equalsIgnoreCase(context.peek().text())) {
                        refParameters.add(context.consume(TokenType.IDENTIFIER, "Expected a formal parameter name after REF."));
                    }
                } else if ("VAL".equalsIgnoreCase(keyword)) {
                    context.advance();
                    while (!context.isAtEnd() && context.check(TokenType.IDENTIFIER) && !"REF".equalsIgnoreCase(context.peek().text())) {
                        valParameters.add(context.consume(TokenType.IDENTIFIER, "Expected a formal parameter name after VAL."));
                    }
                } else {
                    // Unknown keyword in declaration
                    context.getDiagnostics().reportError("Unexpected token '" + keyword + "' in procedure declaration.", procName.fileName(), procName.line());
                    break;
                }
            } else {
                break; // Not an identifier, so no more optional keywords
            }
        }

        if (!context.isAtEnd()) {
            context.consume(TokenType.NEWLINE, "Expected newline after .PROC declaration.");
        }

        // Open scope for procedure-local aliases
        parser.pushRegisterAliasScope();

        List<AstNode> body = new ArrayList<>();
        while (!context.isAtEnd() && !(context.check(TokenType.DIRECTIVE) && context.peek().text().equalsIgnoreCase(".ENDP"))) {
            if (context.match(TokenType.NEWLINE)) continue;
            AstNode statement = parser.declaration();
            if (statement != null) {
                body.add(statement);
            }
        }

        // Close scope for procedure-local aliases
        parser.popRegisterAliasScope();

        if (context.isAtEnd() || !(context.check(TokenType.DIRECTIVE) && context.peek().text().equalsIgnoreCase(".ENDP"))) {
            context.getDiagnostics().reportError("Expected .ENDP to close procedure block.", "Syntax Error", procName.line());
        } else {
            context.advance(); // consume .ENDP
        }

        ProcedureNode procNode = new ProcedureNode(procName, exported, parameters, refParameters, valParameters, body);
        parser.registerProcedure(procNode);
        return procNode;
    }
}
