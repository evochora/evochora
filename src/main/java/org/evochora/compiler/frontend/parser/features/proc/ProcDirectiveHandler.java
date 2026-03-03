package org.evochora.compiler.frontend.parser.features.proc;

import org.evochora.compiler.frontend.parser.IParserDirectiveHandler;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.frontend.parser.RegisterAliasState;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.model.ast.AstNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles the parsing of the <code>.PROC</code> and <code>.ENDP</code> directives, which define a procedure block.
 * Procedures can be exported and can have parameters.
 */
public class ProcDirectiveHandler implements IParserDirectiveHandler {

    /**
     * Parses a procedure definition, including its body, until an <code>.ENDP</code> directive is found.
     * The syntax is <code>[EXPORT] .PROC &lt;name&gt; [WITH &lt;param1&gt; &lt;param2&gt; ...] ... .ENDP</code>.
     * @param context The parsing context.
     * @return A {@link ProcedureNode} representing the parsed procedure.
     */
    @Override
    public AstNode parse(ParsingContext context) {
        context.advance(); // consume .PROC

        Token procName = context.consume(TokenType.IDENTIFIER, "Expected procedure name after .PROC.");
        boolean exported = context.isExported();
        List<Token> parameters = new ArrayList<>();
        List<Token> refParameters = new ArrayList<>();
        List<Token> valParameters = new ArrayList<>();
        // Flexible loop to parse optional keywords like WITH, REF, and VAL
        while (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
            if (context.check(TokenType.IDENTIFIER)) {
                String keyword = context.peek().text();
                if ("WITH".equalsIgnoreCase(keyword)) {
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
        context.state().getOrCreate(RegisterAliasState.class, RegisterAliasState::new).pushScope();

        List<AstNode> body = new ArrayList<>();
        while (!context.isAtEnd() && !(context.check(TokenType.DIRECTIVE) && context.peek().text().equalsIgnoreCase(".ENDP"))) {
            if (context.match(TokenType.NEWLINE)) continue;
            AstNode statement = context.declaration();
            if (statement != null) {
                body.add(statement);
            }
        }

        // Close scope for procedure-local aliases
        context.state().getOrCreate(RegisterAliasState.class, RegisterAliasState::new).popScope();

        if (context.isAtEnd() || !(context.check(TokenType.DIRECTIVE) && context.peek().text().equalsIgnoreCase(".ENDP"))) {
            context.getDiagnostics().reportError("Expected .ENDP to close procedure block.", "Syntax Error", procName.line());
        } else {
            context.advance(); // consume .ENDP
        }

        ProcedureNode procNode = new ProcedureNode(procName, exported, parameters, refParameters, valParameters, body);
        ((Parser) context).registerProcedure(procNode);
        return procNode;
    }
}
