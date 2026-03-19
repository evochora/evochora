package org.evochora.compiler.features.instruction;

import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.InstructionNode;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Default parser statement handler for generic instructions (MOV, ADD, NOP, etc.).
 * Registered as the default handler in {@link org.evochora.compiler.frontend.parser.ParserStatementRegistry},
 * invoked when no keyword-specific handler matches.
 */
public class InstructionParsingHandler implements IParserStatementHandler {

    @Override
    public AstNode parse(ParsingContext context) {
        if (context.match(TokenType.OPCODE)) {
            Token opcode = context.previous();
            List<AstNode> arguments = new ArrayList<>();
            while (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
                arguments.add(context.expression());
            }
            return new InstructionNode(opcode.text(), arguments, opcode.toSourceInfo());
        }

        Token unexpected = context.advance();
        if (unexpected.type() != TokenType.END_OF_FILE && unexpected.type() != TokenType.NEWLINE) {
            context.getDiagnostics().reportError(
                    "Expected instruction or directive, but got '" + unexpected.text() + "'.",
                    unexpected.fileName(), unexpected.line());
        }
        return null;
    }
}
