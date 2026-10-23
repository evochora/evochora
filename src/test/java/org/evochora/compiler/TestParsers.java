package org.evochora.compiler;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.expression.OperationRegistry;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParserStatementRegistry;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.compiler.model.token.Token;

import java.util.List;
import java.util.Map;

/**
 * Builds parsers for tests that parse a token list directly, without the operations of
 * computations a {@link Compiler} would hand them: a computation in an operand reads only as far
 * as it needs none, and an operator in it is unexpected. The instruction set is the runtime's.
 */
public final class TestParsers {

    private TestParsers() {}

    /**
     * Builds a parser that knows no placement of the compilation root and no values.
     *
     * @param tokens            The list of tokens to parse.
     * @param diagnostics       The engine for reporting errors and warnings.
     * @param statementRegistry The pre-built registry of statement handlers.
     * @return The parser, with no root alias chain, no values and no operations.
     */
    public static Parser parser(List<Token> tokens, DiagnosticsEngine diagnostics,
                                ParserStatementRegistry statementRegistry) {
        return parser(tokens, diagnostics, statementRegistry, null, Map.of());
    }

    /**
     * Builds a parser that knows no operations of computations: a computation in an operand reads
     * only as far as it needs none, and an operator in it is unexpected.
     *
     * @param tokens            The list of tokens to parse.
     * @param diagnostics       The engine for reporting errors and warnings.
     * @param statementRegistry The pre-built registry of statement handlers.
     * @param rootAliasChain    The placement of the compilation root, or {@code null} for none.
     * @param values            The lexed text of every value of the compilation's options, by its
     *                          name as the options normalise it.
     * @return The parser, with no operations.
     */
    public static Parser parser(List<Token> tokens, DiagnosticsEngine diagnostics,
                                ParserStatementRegistry statementRegistry, String rootAliasChain,
                                Map<String, List<Token>> values) {
        return new Parser(tokens, diagnostics, statementRegistry, rootAliasChain, values,
                new OperationRegistry(), new RuntimeInstructionSetAdapter());
    }
}
