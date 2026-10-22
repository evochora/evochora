package org.evochora.compiler.expression.parser;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.expression.IOperation;
import org.evochora.compiler.expression.Level;
import org.evochora.compiler.expression.Notation;
import org.evochora.compiler.expression.OperationRegistry;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.model.expression.Expression;
import org.evochora.compiler.model.expression.LiteralExpression;
import org.evochora.compiler.model.expression.NameExpression;
import org.evochora.compiler.model.expression.OperationExpression;
import org.evochora.compiler.model.expression.Value;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads the tokens of one computation into an {@link Expression}. It knows the forms of the
 * language and asks the {@link OperationRegistry} what a token means: an operator is looked up by
 * its text and the notation its position gives it, and the level the registry names decides how
 * it binds (see {@link Level}). The forms:
 * <ul>
 *   <li>a number, a name ({@code @} included), a parenthesis {@code (a)};</li>
 *   <li>a call {@code NAME(a, b)}, a word directly followed by its parenthesis, of a function the
 *       registry knows; a prefix word before its parenthesis, {@code NOT(A)}, is the prefix
 *       applied to the parenthesis;</li>
 *   <li>a prefix {@code OP a}, an infix {@code a OP b}, and a chain {@code a|b|c} of one chain
 *       operator, read as one operation on all its parts;</li>
 *   <li>an index {@code a[b]}, the operation {@link OperationRegistry#INDEX};</li>
 *   <li>a molecule type the instruction set knows, followed by {@code :}, read as a type literal
 *       to which the typing operator applies, so {@code DATA:5} is the typing of {@code DATA}
 *       and {@code 5}; any other word before a colon is a name.</li>
 * </ul>
 * A word operator or a function may arrive as an opcode token, since {@code NOT} is a mnemonic
 * too, and is read like an identifier. The parser fixes no operator but the brackets, the
 * parentheses, the comma and the recognition of a molecule type before the typing colon.
 * <p>
 * An operator of the unordered level stands next to itself only: next to a multiplicative,
 * additive or chain operator, or to a different unordered operator, the parser asks for
 * parentheses.
 */
public final class ExpressionParser {

    private final OperationRegistry operations;
    private final IInstructionSet isa;

    /**
     * Creates a parser over the operations of a compilation.
     *
     * @param operations The registry the features filled.
     * @param isa        The instruction set, whose molecule types the typing colon recognises.
     */
    public ExpressionParser(OperationRegistry operations, IInstructionSet isa) {
        this.operations = operations;
        this.isa = isa;
    }

    /**
     * Reads the tokens of one computation.
     *
     * @param tokens The tokens, ending at the end of the list or at an end-of-file token; the
     *               parentheses that mark a computation in an operand are not among them.
     * @return The tree, or the error with the position of the token at fault.
     */
    public ParseResult parse(List<Token> tokens) {
        Reader reader = new Reader(tokens);
        try {
            if (reader.atEnd()) {
                throw new Failure("Expected an expression.", reader.endSource());
            }
            Parsed parsed = reader.level(Level.UNORDERED);
            if (!reader.atEnd()) {
                throw reader.unexpected(reader.peek());
            }
            return new ParseResult.Parsed(parsed.expression());
        } catch (Failure failure) {
            return new ParseResult.Failed(failure.getMessage(), failure.at);
        }
    }

    /**
     * A part of the tree as read, with what the rule for the unordered level needs to know of it.
     *
     * @param expression The tree.
     * @param start      The position of its first token, which a parenthesis around it is.
     * @param operator   The name of the operator at its top if it stands there without
     *                   parentheses; {@code null} for an atom, a call or a parenthesis.
     * @param level      The level of that operator; {@code null} with it.
     * @param operatorAt The position of that operator's token; {@code null} with it.
     */
    private record Parsed(Expression expression, SourceInfo start, String operator, Level level, SourceInfo operatorAt) {

        static Parsed atom(Expression expression, SourceInfo start) {
            return new Parsed(expression, start, null, null, null);
        }

        /**
         * Tells whether an operator of the unordered level next to this part needs parentheses
         * around it: when an operator wider than the prefixes stands at its top.
         */
        boolean needsParenthesesNextTo(String unordered) {
            return operator != null && level.compareTo(Level.PREFIX) > 0
                    && !(level == Level.UNORDERED && operator.equals(unordered));
        }
    }

    /**
     * An error of the computation, ending the reading.
     */
    private static final class Failure extends RuntimeException {
        private final transient SourceInfo at;

        Failure(String message, SourceInfo at) {
            super(message, null, false, false);
            this.at = at;
        }
    }

    /**
     * The reading of one token list: the position and the rules, one method per level.
     */
    private final class Reader {
        private final List<Token> tokens;
        private int current;

        Reader(List<Token> tokens) {
            this.tokens = tokens;
        }

        Parsed level(Level level) {
            return switch (level) {
                case INDEX -> postfix();
                case TYPING, MULTIPLICATIVE, ADDITIVE -> leftToRight(level);
                case PREFIX -> prefix();
                case CHAIN -> chain();
                case UNORDERED -> unordered();
            };
        }

        /**
         * The infix operators of one ordered level, left to right.
         */
        private Parsed leftToRight(Level level) {
            Parsed left = level(tighter(level));
            Optional<IOperation> operator;
            while ((operator = infixAt(level)).isPresent()) {
                Token token = advance();
                Parsed right = operand(token, level.operandLevel());
                left = new Parsed(operation(operator.get(), List.of(left.expression(), right.expression()), left.start()),
                        left.start(), operator.get().name(), level, token.source());
            }
            return left;
        }

        private Parsed prefix() {
            Token token = peek();
            Optional<IOperation> operator = find(token, Notation.PREFIX);
            if (operator.isEmpty()) {
                return level(tighter(Level.PREFIX));
            }
            advance();
            Parsed operand = operand(token, Level.PREFIX.operandLevel());
            return new Parsed(operation(operator.get(), List.of(operand.expression()), token.source()),
                    token.source(), operator.get().name(), Level.PREFIX, token.source());
        }

        /**
         * A chain of one chain operator, one operation on all its parts.
         */
        private Parsed chain() {
            Parsed first = level(tighter(Level.CHAIN));
            Optional<IOperation> operator = find(peek(), Notation.CHAIN);
            if (operator.isEmpty()) {
                return first;
            }
            String name = operator.get().name();
            SourceInfo operatorAt = peek().source();
            List<Expression> parts = new ArrayList<>(List.of(first.expression()));
            Optional<IOperation> next;
            while ((next = find(peek(), Notation.CHAIN)).isPresent()) {
                Token token = advance();
                if (!next.get().name().equals(name)) {
                    throw new Failure(needParentheses(name, next.get().name()), token.source());
                }
                parts.add(operand(token, Level.CHAIN.operandLevel()).expression());
            }
            return new Parsed(operation(operator.get(), parts, first.start()), first.start(), name, Level.CHAIN, operatorAt);
        }

        /**
         * The operators of the unordered level: one operator with itself, left to right, and
         * nothing wider than a prefix next to it without parentheses.
         */
        private Parsed unordered() {
            Parsed left = level(tighter(Level.UNORDERED));
            Optional<IOperation> operator = infixAt(Level.UNORDERED);
            if (operator.isEmpty()) {
                return left;
            }
            String name = operator.get().name();
            if (left.needsParenthesesNextTo(name)) {
                throw new Failure(needParentheses(left.operator(), name), peek().source());
            }
            Optional<IOperation> next;
            while ((next = infixAt(Level.UNORDERED)).isPresent()) {
                Token token = advance();
                if (!next.get().name().equals(name)) {
                    throw new Failure(needParentheses(name, next.get().name()), token.source());
                }
                Parsed right = operand(token, Level.UNORDERED.operandLevel());
                if (right.needsParenthesesNextTo(name)) {
                    throw new Failure(needParentheses(name, right.operator()), right.operatorAt());
                }
                left = new Parsed(operation(operator.get(), List.of(left.expression(), right.expression()), left.start()),
                        left.start(), name, Level.UNORDERED, token.source());
            }
            return left;
        }

        /**
         * An atom followed by any number of indexes.
         */
        private Parsed postfix() {
            Parsed base = primary();
            while (check(TokenType.OPEN_BRACKET)) {
                Optional<IOperation> index = operations.find(OperationRegistry.INDEX, Notation.INFIX);
                if (index.isEmpty()) {
                    throw unexpected(peek());
                }
                Token open = advance();
                Parsed inside = operand(open, Level.INDEX.operandLevel());
                close(TokenType.CLOSE_BRACKET, "]");
                base = new Parsed(operation(index.get(), List.of(base.expression(), inside.expression()), base.start()),
                        base.start(), index.get().name(), Level.INDEX, open.source());
            }
            return base;
        }

        private Parsed primary() {
            Token token = peek();
            switch (token.type()) {
                case NUMBER -> {
                    advance();
                    return Parsed.atom(new LiteralExpression(new Value.Number((Long) token.value()), token.source(), token.after()),
                            token.source());
                }
                case REGISTER -> throw new Failure("Register '" + token.text() + "' cannot be used in an expression.", token.source());
                case OPEN_PARENTHESIS, CALL_PARENTHESIS -> {
                    advance();
                    Parsed inside = operand(token, Level.UNORDERED);
                    close(TokenType.CLOSE_PARENTHESIS, ")");
                    return Parsed.atom(endingAt(inside.expression(), readEnd()), token.source());
                }
                case IDENTIFIER, OPCODE -> {
                    return word(token);
                }
                default -> throw unexpected(token);
            }
        }

        /**
         * A word: a type literal before the typing colon, a call, or a name.
         */
        private Parsed word(Token token) {
            String word = token.text().toUpperCase(Locale.ROOT);
            TokenType next = typeAt(current + 1);
            if (next == TokenType.COLON && isa.moleculeType(word).isPresent()) {
                advance();
                return Parsed.atom(new LiteralExpression(new Value.Type(word), token.source(), token.after()), token.source());
            }
            if (next == TokenType.CALL_PARENTHESIS) {
                Optional<IOperation> function = operations.find(word, Notation.FUNCTION);
                if (function.isEmpty()) {
                    throw new Failure("Unknown function '" + token.text() + "'.", token.source());
                }
                advance();
                Token open = advance();
                List<Expression> arguments = new ArrayList<>();
                if (check(TokenType.CLOSE_PARENTHESIS)) {
                    advance();
                } else {
                    Token before = open;
                    while (true) {
                        arguments.add(operand(before, Level.UNORDERED).expression());
                        if (!check(TokenType.COMMA)) {
                            break;
                        }
                        before = advance();
                    }
                    close(TokenType.CLOSE_PARENTHESIS, ")");
                }
                return Parsed.atom(operation(function.get(), arguments, token.source()), token.source());
            }
            if (find(token, Notation.INFIX).isPresent() || find(token, Notation.CHAIN).isPresent()) {
                throw unexpected(token);
            }
            advance();
            return Parsed.atom(new NameExpression(token.text(), token.source(), token.after()), token.source());
        }

        /**
         * Reads the operand that follows a token at the given level, or reports that none follows.
         */
        private Parsed operand(Token after, Level level) {
            if (!startsExpression()) {
                throw new Failure("Expected an expression after '" + after.text() + "'.", after.source());
            }
            return level(level);
        }

        private boolean startsExpression() {
            if (atEnd()) {
                return false;
            }
            Token token = peek();
            return switch (token.type()) {
                case NUMBER, REGISTER, IDENTIFIER, OPCODE, OPEN_PARENTHESIS, CALL_PARENTHESIS -> true;
                default -> find(token, Notation.PREFIX).isPresent();
            };
        }

        private void close(TokenType type, String text) {
            if (atEnd()) {
                throw new Failure("Expected '" + text + "'.", endSource());
            }
            if (!check(type)) {
                throw unexpected(peek());
            }
            advance();
        }

        /**
         * The operation of the given notation a token names, if it is an operator token: a
         * registered symbol, the pipe, the colon, or a word.
         */
        private Optional<IOperation> find(Token token, Notation notation) {
            if (token == null) {
                return Optional.empty();
            }
            String text = switch (token.type()) {
                case SYMBOL, PIPE, COLON -> token.text();
                case IDENTIFIER, OPCODE -> token.text().toUpperCase(Locale.ROOT);
                default -> null;
            };
            return text == null ? Optional.empty() : operations.find(text, notation);
        }

        /**
         * The infix operator of the given level the next token names.
         */
        private Optional<IOperation> infixAt(Level level) {
            return find(peek(), Notation.INFIX).filter(operator -> operator.level() == level);
        }

        Failure unexpected(Token token) {
            return new Failure("Unexpected '" + token.text() + "'.", token.source());
        }

        boolean atEnd() {
            return current >= tokens.size() || tokens.get(current).type() == TokenType.END_OF_FILE;
        }

        Token peek() {
            return atEnd() ? null : tokens.get(current);
        }

        private boolean check(TokenType type) {
            return !atEnd() && tokens.get(current).type() == type;
        }

        private TokenType typeAt(int index) {
            return index < tokens.size() ? tokens.get(index).type() : TokenType.END_OF_FILE;
        }

        private Token advance() {
            return tokens.get(current++);
        }

        /**
         * The position right after the last token read, where a node read up to here ends.
         */
        private SourceInfo readEnd() {
            return tokens.get(current - 1).after();
        }

        /**
         * The operation on the given operands, ending after the last token read.
         */
        private OperationExpression operation(IOperation operator, List<Expression> operands, SourceInfo at) {
            return new OperationExpression(operator.name(), operator.notation() == Notation.FUNCTION, operands, at, readEnd());
        }

        /**
         * The position errors at the end of the computation are reported at: the end-of-file
         * token's, or the last token's.
         */
        SourceInfo endSource() {
            if (current < tokens.size()) {
                return tokens.get(current).source();
            }
            return tokens.isEmpty() ? null : tokens.getLast().source();
        }
    }

    /**
     * Puts the end of a node to the given position, for a node read inside a parenthesis, which
     * ends after the {@code )}.
     */
    private static Expression endingAt(Expression expression, SourceInfo end) {
        return switch (expression) {
            case LiteralExpression literal -> new LiteralExpression(literal.value(), literal.sourceInfo(), end);
            case NameExpression name -> new NameExpression(name.name(), name.sourceInfo(), end);
            case OperationExpression operation ->
                    new OperationExpression(operation.operation(), operation.function(), operation.operands(), operation.sourceInfo(), end);
        };
    }

    private static Level tighter(Level level) {
        return Level.values()[level.ordinal() - 1];
    }

    private static String needParentheses(String first, String second) {
        return "'" + first + "' and '" + second + "' need parentheses.";
    }
}
