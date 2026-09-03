package org.evochora.compiler.features.reg;

import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.frontend.parser.IParsingContext;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.isa.IInstructionSet.RegisterBankInfo;

/**
 * Handler for the {@code .REG} directive.
 * Parses a register alias definition for any register bank and validates
 * that the target register is allowed in the current scope.
 */
public class RegDirectiveHandler implements IParserStatementHandler {

    private final IInstructionSet isa;

    /**
     * @param isa The instruction set, whose register banks decide what a register text names.
     */
    public RegDirectiveHandler(IInstructionSet isa) {
        this.isa = isa;
    }

    /**
     * Parses a {@code .REG} directive.
     * Expected format: {@code .REG <ALIAS_NAME> <REGISTER>}
     *
     * <p>Whether the register exists and may be named at all, the lexer has checked; this
     * handler checks what only the parser knows, whether the current scope opens the bank
     * (a procedure-scoped bank requires a .PROC block).
     *
     * @param context the parsing context
     * @return a {@link RegNode} or {@code null} if parsing fails
     */
    @Override
    public AstNode parse(IParsingContext context) {
        context.advance(); // consume .REG

        // Alias name can be IDENTIFIER (e.g., DR_A) or REGISTER (e.g., %DR_A)
        Token name;
        if (context.check(TokenType.IDENTIFIER)) {
            name = context.advance();
        } else if (context.check(TokenType.REGISTER)) {
            name = context.advance();
        } else {
            name = context.consume(TokenType.IDENTIFIER, "Expected an alias name after .REG.");
        }

        // Target must be an explicit register token
        Token register;
        if (context.check(TokenType.REGISTER)) {
            register = context.advance();
        } else {
            register = context.consume(TokenType.REGISTER, "Expected a register after the alias name in .REG.");
        }

        if (name == null || register == null) {
            return null;
        }

        int line = register.line();
        RegisterBankInfo bank = isa.parseRegister(register.text())
                .orElseThrow(() -> new IllegalStateException("REGISTER token the instruction set cannot read: " + register.text()))
                .bank();

        // The lexer has reported a register source may not name; nothing is left to alias
        if (bank.forbidden()) {
            return null;
        }

        if (!bank.alwaysAvailable() && !context.state().isRegisterBankAvailable(bank.name())) {
            context.getDiagnostics().reportError(
                    "Register '" + register.text() + "' is only available inside a procedure.",
                    register.fileName(), line);
            return null;
        }

        return new RegNode(name.text(), register.text(), name.toSourceInfo());
    }
}
