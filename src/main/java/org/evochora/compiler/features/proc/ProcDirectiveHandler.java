package org.evochora.compiler.features.proc;

import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.frontend.parser.IParsingContext;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.isa.IInstructionSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handler for the {@code .PROC} directive.
 * Parses procedure declarations with optional parameter keywords:
 * REF/VAL (scalar by reference/value), LREF/LVAL (location by reference/value).
 */
public class ProcDirectiveHandler implements IParserStatementHandler {

    private static final Set<String> PARAM_KEYWORDS = Set.of("REF", "VAL", "LREF", "LVAL");

    private final IInstructionSet isa;

    /**
     * Creates the handler for an instruction set.
     *
     * @param isa The instruction set, which names the register banks a procedure body opens up.
     */
    public ProcDirectiveHandler(IInstructionSet isa) {
        this.isa = isa;
    }

    @Override
    public boolean supportsExport() { return true; }

    @Override
    public AstNode parse(IParsingContext context) {
        context.advance(); // consume .PROC

        Token procName = context.consume(TokenType.IDENTIFIER, "Expected procedure name after .PROC.");
        boolean exported = context.isExported();
        List<ProcedureNode.ParamDecl> refParameters = new ArrayList<>();
        List<ProcedureNode.ParamDecl> valParameters = new ArrayList<>();
        List<ProcedureNode.ParamDecl> lrefParameters = new ArrayList<>();
        List<ProcedureNode.ParamDecl> lvalParameters = new ArrayList<>();

        // Each parameter keyword opens a list; the names that follow it, up to the next keyword,
        // are that list's formal parameters.
        Map<String, List<ProcedureNode.ParamDecl>> parametersByKeyword = Map.of(
                "REF", refParameters, "VAL", valParameters, "LREF", lrefParameters, "LVAL", lvalParameters);
        while (!context.isAtEnd() && context.check(TokenType.IDENTIFIER)) {
            String keyword = context.peek().text().toUpperCase();
            List<ProcedureNode.ParamDecl> target = parametersByKeyword.get(keyword);
            if (target == null) {
                context.getDiagnostics().reportError("Unexpected '" + context.peek().text() + "' in .PROC: expected REF, VAL, LREF or LVAL.", procName.fileName(), procName.line());
                break;
            }
            context.advance();
            while (!context.isAtEnd() && context.check(TokenType.IDENTIFIER) && !isParamKeyword(context.peek().text())) {
                Token p = context.consume(TokenType.IDENTIFIER, "Expected a formal parameter name after " + keyword + ".");
                target.add(new ProcedureNode.ParamDecl(p.text(), p.toSourceInfo()));
            }
        }

        if (!context.isAtEnd()) {
            context.consume(TokenType.NEWLINE, "Expected newline after .PROC declaration.");
        }

        context.state().pushScope();
        String[] procScopedBanks = isa.registerBanks().stream()
                .filter(b -> b.procScoped() && !b.forbidden())
                .map(IInstructionSet.RegisterBankInfo::name)
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
            context.getDiagnostics().reportError(".PROC '" + procName.text() + "' is not closed; expected .ENDP.", procName.fileName(), procName.line());
        } else {
            context.advance(); // consume .ENDP
        }

        return new ProcedureNode(procName.text(), exported, refParameters, valParameters, lrefParameters, lvalParameters, body, procName.toSourceInfo());
    }

    private static boolean isParamKeyword(String text) {
        return PARAM_KEYWORDS.contains(text.toUpperCase());
    }
}
