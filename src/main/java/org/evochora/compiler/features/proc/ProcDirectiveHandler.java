package org.evochora.compiler.features.proc;

import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.runtime.isa.RegisterBank;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Handler for the {@code .PROC} directive.
 * Parses procedure declarations with optional parameter keywords:
 * WITH (legacy scalar), REF/VAL (scalar by reference/value), LREF/LVAL (location by reference/value).
 */
public class ProcDirectiveHandler implements IParserStatementHandler {

    private static final Set<String> PARAM_KEYWORDS = Set.of("WITH", "REF", "VAL", "LREF", "LVAL");

    @Override
    public boolean supportsExport() { return true; }

    @Override
    public AstNode parse(ParsingContext context) {
        context.advance(); // consume .PROC

        Token procName = context.consume(TokenType.IDENTIFIER, "Expected procedure name after .PROC.");
        boolean exported = context.isExported();
        List<ProcedureNode.ParamDecl> parameters = new ArrayList<>();
        List<ProcedureNode.ParamDecl> refParameters = new ArrayList<>();
        List<ProcedureNode.ParamDecl> valParameters = new ArrayList<>();
        List<ProcedureNode.ParamDecl> lrefParameters = new ArrayList<>();
        List<ProcedureNode.ParamDecl> lvalParameters = new ArrayList<>();

        while (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
            if (context.check(TokenType.IDENTIFIER)) {
                String keyword = context.peek().text();
                if ("WITH".equalsIgnoreCase(keyword)) {
                    context.advance();
                    while (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
                        Token p = context.consume(TokenType.IDENTIFIER, "Expected a formal parameter name after WITH.");
                        parameters.add(new ProcedureNode.ParamDecl(p.text(), p.toSourceInfo()));
                    }
                    break;
                } else if ("REF".equalsIgnoreCase(keyword)) {
                    context.advance();
                    while (!context.isAtEnd() && context.check(TokenType.IDENTIFIER) && !isParamKeyword(context.peek().text())) {
                        Token p = context.consume(TokenType.IDENTIFIER, "Expected a formal parameter name after REF.");
                        refParameters.add(new ProcedureNode.ParamDecl(p.text(), p.toSourceInfo()));
                    }
                } else if ("VAL".equalsIgnoreCase(keyword)) {
                    context.advance();
                    while (!context.isAtEnd() && context.check(TokenType.IDENTIFIER) && !isParamKeyword(context.peek().text())) {
                        Token p = context.consume(TokenType.IDENTIFIER, "Expected a formal parameter name after VAL.");
                        valParameters.add(new ProcedureNode.ParamDecl(p.text(), p.toSourceInfo()));
                    }
                } else if ("LREF".equalsIgnoreCase(keyword)) {
                    context.advance();
                    while (!context.isAtEnd() && context.check(TokenType.IDENTIFIER) && !isParamKeyword(context.peek().text())) {
                        Token p = context.consume(TokenType.IDENTIFIER, "Expected a formal parameter name after LREF.");
                        lrefParameters.add(new ProcedureNode.ParamDecl(p.text(), p.toSourceInfo()));
                    }
                } else if ("LVAL".equalsIgnoreCase(keyword)) {
                    context.advance();
                    while (!context.isAtEnd() && context.check(TokenType.IDENTIFIER) && !isParamKeyword(context.peek().text())) {
                        Token p = context.consume(TokenType.IDENTIFIER, "Expected a formal parameter name after LVAL.");
                        lvalParameters.add(new ProcedureNode.ParamDecl(p.text(), p.toSourceInfo()));
                    }
                } else {
                    context.getDiagnostics().reportError("Unexpected token '" + keyword + "' in procedure declaration.", procName.fileName(), procName.line());
                    break;
                }
            } else {
                break;
            }
        }

        if (!context.isAtEnd()) {
            context.consume(TokenType.NEWLINE, "Expected newline after .PROC declaration.");
        }

        context.state().pushScope();
        String[] procScopedBanks = RegisterBank.allProcScoped().stream()
                .filter(b -> !b.isForbidden)
                .map(b -> b.prefix.substring(1))
                .toArray(String[]::new);
        context.state().addAvailableRegisterBanks(procScopedBanks);

        List<AstNode> body = new ArrayList<>();
        while (!context.isAtEnd() && !(context.check(TokenType.DIRECTIVE) && context.peek().text().equalsIgnoreCase(".ENDP"))) {
            if (context.match(TokenType.NEWLINE)) continue;
            AstNode statement = context.declaration();
            if (statement != null) {
                body.add(statement);
            }
        }

        context.state().removeAvailableRegisterBanks(procScopedBanks);
        context.state().popScope();

        if (context.isAtEnd() || !(context.check(TokenType.DIRECTIVE) && context.peek().text().equalsIgnoreCase(".ENDP"))) {
            context.getDiagnostics().reportError("Expected .ENDP to close procedure block.", procName.fileName(), procName.line());
        } else {
            context.advance(); // consume .ENDP
        }

        return new ProcedureNode(procName.text(), exported, parameters, refParameters, valParameters, lrefParameters, lvalParameters, body, procName.toSourceInfo());
    }

    private static boolean isParamKeyword(String text) {
        return PARAM_KEYWORDS.contains(text.toUpperCase());
    }
}
