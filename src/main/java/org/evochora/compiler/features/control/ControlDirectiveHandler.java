package org.evochora.compiler.features.control;

import org.evochora.compiler.diagnostics.ErrorRecoveryException;
import org.evochora.compiler.frontend.BlockReader;
import org.evochora.compiler.frontend.DirectiveLine;
import org.evochora.compiler.frontend.parser.IParserBlockHandler;
import org.evochora.compiler.frontend.parser.IParsingContext;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Handler for the block {@code .CONTROL <Block>} … {@code .CASE <Case>} … {@code .ENDCONTROL},
 * whose extent the parser has read. Reads the block name from the opener's line, has the parser
 * parse the head and every case, and builds a {@link ControlNode} with its {@link ControlCase}s.
 * <p>
 * {@code EXPORT} may stand before each of the three words. A missing block name, a word after
 * it, a {@code .CASE} without a name and a word after the case name are reported; the handler
 * then gives up, and the parser leaves the whole block behind. The handler keeps no state.
 */
public class ControlDirectiveHandler implements IParserBlockHandler {

    @Override
    public boolean supportsExport(String word) {
        return true;
    }

    @Override
    public AstNode parse(IParsingContext context, BlockReader.Block block) {
        context.advance(); // consume .CONTROL

        Token blockName = context.consume(TokenType.IDENTIFIER, "Expected block name after .CONTROL.");
        boolean exported = context.isExported();
        if (!context.isAtEnd()) {
            context.consume(TokenType.NEWLINE, "Expected newline after .CONTROL declaration.");
        }

        // The words that end a part: every divider, then the closer.
        List<Integer> words = new ArrayList<>(block.dividers());
        words.add(block.closer());

        List<AstNode> head = context.statements(block.bodyStart(), block.partEnd(words.getFirst()));
        List<ControlCase> cases = new ArrayList<>();
        for (int k = 0; k < block.dividers().size(); k++) {
            int divider = block.dividers().get(k);
            DirectiveLine line = context.lineOf(divider);
            Token caseName = caseName(context, context.tokenAt(divider), line);
            List<AstNode> statements = context.statements(line.next(), block.partEnd(words.get(k + 1)));
            cases.add(new ControlCase(caseName.text(), block.prefixed().contains(divider), statements, caseName.source()));
        }

        return new ControlNode(blockName.text(), exported, head, cases, block.prefixed().contains(block.closer()),
                blockName.source(), context.tokenAt(block.closer()).source());
    }

    /**
     * Returns the name of a case, the one operand on the line of its {@code .CASE}.
     *
     * @param context The parsing context, for its diagnostics.
     * @param divider The {@code .CASE} token, where an error is reported.
     * @param line    The line of the {@code .CASE}.
     * @return The token of the case name.
     * @throws ErrorRecoveryException if the line holds no name or a word after it; the error has
     *                                been reported.
     */
    private static Token caseName(IParsingContext context, Token divider, DirectiveLine line) {
        List<Token> operands = line.operands();
        if (operands.isEmpty() || operands.getFirst().type() != TokenType.IDENTIFIER) {
            throw report(context, divider, "Expected case name after .CASE.");
        }
        if (operands.size() > 1) {
            throw report(context, divider, "Expected newline after .CASE " + operands.getFirst().text() + ".");
        }
        return operands.getFirst();
    }

    private static ErrorRecoveryException report(IParsingContext context, Token at, String message) {
        context.getDiagnostics().reportError(message, at.source().fileName(), at.source().lineNumber());
        return new ErrorRecoveryException(message);
    }
}
