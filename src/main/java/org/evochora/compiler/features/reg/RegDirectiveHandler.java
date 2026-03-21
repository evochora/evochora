package org.evochora.compiler.features.reg;

import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.runtime.Config;

import java.util.Set;

/**
 * Handler for the {@code .REG} directive.
 * Parses a register alias definition for any register bank and validates
 * that the target register is allowed in the current scope.
 */
public class RegDirectiveHandler implements IParserStatementHandler {

    private static final Set<String> FORBIDDEN_BANKS = Set.of("FDR");

    /**
     * Parses a {@code .REG} directive.
     * Expected format: {@code .REG <ALIAS_NAME> <REGISTER>}
     *
     * <p>Validation order:
     * <ol>
     *   <li>Forbidden-bank check (FDR cannot be aliased)</li>
     *   <li>Scope availability check (PDR requires a .PROC block)</li>
     *   <li>Bounds check (register index within Config limits)</li>
     * </ol>
     *
     * @param context the parsing context
     * @return a {@link RegNode} or {@code null} if parsing fails
     */
    @Override
    public AstNode parse(ParsingContext context) {
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

        String regText = register.text().toUpperCase();
        int line = register.line();

        // Extract bank prefix and index
        String bank;
        int index;
        try {
            if (regText.startsWith("%PDR")) {
                bank = "PDR";
                index = Integer.parseInt(regText.substring(4));
            } else if (regText.startsWith("%FDR")) {
                bank = "FDR";
                index = Integer.parseInt(regText.substring(4));
            } else if (regText.startsWith("%DR")) {
                bank = "DR";
                index = Integer.parseInt(regText.substring(3));
            } else if (regText.startsWith("%LR")) {
                bank = "LR";
                index = Integer.parseInt(regText.substring(3));
            } else {
                context.getDiagnostics().reportError(
                        "Unknown register bank in '" + register.text() + "'.", register.fileName(), line);
                return null;
            }
        } catch (NumberFormatException e) {
            context.getDiagnostics().reportError(
                    "Invalid register index in '" + register.text() + "'.", register.fileName(), line);
            return null;
        }

        // 1. Forbidden-bank check
        if (FORBIDDEN_BANKS.contains(bank)) {
            context.getDiagnostics().reportError(
                    "Register " + register.text() + " cannot be aliased — "
                            + bank + " registers are managed by the CALL binding mechanism.",
                    register.fileName(), line);
            return null;
        }

        // 2. Scope availability check
        if (!context.state().isRegisterBankAvailable(bank)) {
            context.getDiagnostics().reportError(
                    "Register " + register.text() + " is not available in the current scope.",
                    register.fileName(), line);
            return null;
        }

        // 3. Bounds check
        int maxRegisters = switch (bank) {
            case "DR" -> Config.NUM_DATA_REGISTERS;
            case "PDR" -> Config.NUM_PDR_REGISTERS;
            case "LR" -> Config.NUM_LOCATION_REGISTERS;
            default -> throw new IllegalStateException("Unhandled bank: " + bank);
        };
        if (index < 0 || index >= maxRegisters) {
            context.getDiagnostics().reportError(
                    "Register index " + index + " is out of bounds for " + bank
                            + " bank. Valid range: 0-" + (maxRegisters - 1) + ".",
                    register.fileName(), line);
            return null;
        }

        return new RegNode(name.text(), register.text(), name.toSourceInfo());
    }
}
