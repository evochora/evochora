package org.evochora.compiler.frontend.parser;

import org.evochora.compiler.model.expression.Value;
import org.evochora.compiler.model.ast.LiteralNode;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.diagnostics.ErrorRecoveryException;
import org.evochora.compiler.frontend.BlockReader;
import org.evochora.compiler.frontend.DirectiveLine;
import org.evochora.compiler.frontend.Export;
import org.evochora.compiler.frontend.SignedNumber;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IdentifierNode;

import org.evochora.compiler.model.ast.OperandNode;
import org.evochora.compiler.model.ast.RegisterNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The main parser for the assembly language. It consumes a list of tokens
 * from the {@link org.evochora.compiler.frontend.lexer.Tokenizer} and produces an Abstract Syntax Tree (AST).
 * All statement dispatch goes through the {@link ParserStatementRegistry}. A statement that
 * opens a registered kind of block is read as a block first, by the rules of
 * {@link BlockReader}, and its handler is called with the block only when the block is whole.
 */
public class Parser implements IParsingContext {

    private final List<Token> tokens;
    private final DiagnosticsEngine diagnostics;
    private final ParserStatementRegistry statementRegistry;
    private final BlockReader blockReader;
    private final String rootAliasChain;
    private final Map<String, List<Token>> values;
    private int current = 0;

    private final ParserState parserState = new ParserState();
    private boolean currentExported = false;

    /**
     * Constructs a new Parser that knows no placement of the compilation root and no values.
     * @param tokens The list of tokens to parse.
     * @param diagnostics The engine for reporting errors and warnings.
     * @param statementRegistry The pre-built registry of statement handlers.
     */
    public Parser(List<Token> tokens, DiagnosticsEngine diagnostics,
                  ParserStatementRegistry statementRegistry) {
        this(tokens, diagnostics, statementRegistry, null, Map.of());
    }

    /**
     * Constructs a new Parser for a compilation.
     * @param tokens The list of tokens to parse.
     * @param diagnostics The engine for reporting errors and warnings.
     * @param statementRegistry The pre-built registry of statement handlers.
     * @param rootAliasChain The placement of the compilation root, or {@code null} for none.
     * @param values The lexed text of every value of the compilation's options, by its name as
     *               the options normalise it.
     */
    public Parser(List<Token> tokens, DiagnosticsEngine diagnostics,
                  ParserStatementRegistry statementRegistry, String rootAliasChain,
                  Map<String, List<Token>> values) {
        this.tokens = tokens;
        this.diagnostics = diagnostics;
        this.statementRegistry = statementRegistry;
        this.blockReader = new BlockReader(statementRegistry::blockKindOf, false, Export::isWord, diagnostics);
        this.rootAliasChain = rootAliasChain;
        this.values = Map.copyOf(values);
    }

    /**
     * Parses the entire token stream and returns a list of top-level AST nodes.
     * @return A list of parsed {@link AstNode}s.
     */
    public List<AstNode> parse() {
        return statements(current, tokens.size());
    }

    @Override
    public List<AstNode> statements(int from, int to) {
        current = from;
        List<AstNode> statements = new ArrayList<>();
        while (current < to && !isAtEnd()) {
            if (match(TokenType.NEWLINE)) {
                continue;
            }
            AstNode statement = statement();
            if (statement != null) {
                statements.add(statement);
            }
        }
        current = Math.max(current, to);
        return statements;
    }

    /**
     * Parses a single statement. Handles the EXPORT keyword, then dispatches by keyword: a
     * block opener through the block reader and the block handler, a keyword through the
     * statement registry, a closer or divider outside any block as a stray, an unknown directive
     * as an error, anything else through the default handler.
     * @return The parsed {@link AstNode}, or null if the statement produced none or an error
     *         occurred.
     */
    private AstNode statement() {
        try {
            currentExported = false;
            if (Export.isWord(peek())) {
                currentExported = true;
                advance();
            }

            Token keyword = peek();
            Optional<IParserBlockHandler> blockHandler = statementRegistry.blockHandlerOf(keyword.text());
            if (blockHandler.isPresent()) {
                return block(blockHandler.get());
            }

            // Keyword lookup in statement registry (directives, opcodes, etc.)
            Optional<IParserStatementHandler> handler = statementRegistry.get(keyword.text());
            if (handler.isPresent()) {
                if (currentExported && !handler.get().supportsExport()) {
                    reportExport(keyword);
                }
                return handler.get().parse(this);
            }

            // A closer or divider the walk reaches stands outside any block; its statement goes with it.
            if (blockReader.reportStray(tokens, current)) {
                skipStatement();
                return null;
            }

            // After preprocessing, all remaining directives must have a registered handler
            if (check(TokenType.DIRECTIVE)) {
                Token directive = advance();
                diagnostics.reportError(
                        "Unknown directive '" + directive.text() + "'.",
                        directive.source().fileName(), directive.source().lineNumber());
                return null;
            }

            // Default handler for generic instructions
            Optional<IParserStatementHandler> defaultHandler = statementRegistry.getDefault();
            if (defaultHandler.isPresent()) {
                if (currentExported) {
                    reportExport(keyword);
                }
                return defaultHandler.get().parse(this);
            }

            Token unexpected = advance();
            if (unexpected.type() != TokenType.END_OF_FILE && unexpected.type() != TokenType.NEWLINE) {
                diagnostics.reportError("Expected instruction or directive, but got '" + unexpected.text() + "'.",
                        unexpected.source().fileName(), unexpected.source().lineNumber());
            }
            return null;
        } catch (ErrorRecoveryException ex) {
            synchronize();
            return null;
        }
    }

    /**
     * Reads the block whose opener the parser stands on and hands it to its handler when it is
     * whole; a broken block has been reported by the reader and is left behind. Before the
     * handler runs, EXPORT before the opener, a divider or the closer is reported where the
     * handler does not take it. The parser continues after the block in every case, so a handler
     * that gives up leaves its whole block behind.
     */
    private AstNode block(IParserBlockHandler handler) {
        BlockReader.Block block = blockReader.read(tokens, current);
        if (!block.whole()) {
            current = block.end();
            return null;
        }
        Token opener = tokens.get(block.opener());
        if (currentExported && !handler.supportsExport(opener.text())) {
            reportExport(opener);
        }
        for (int divider : block.dividers()) {
            reportExportUnlessTaken(handler, block, divider);
        }
        reportExportUnlessTaken(handler, block, block.closer());
        try {
            return handler.parse(this, block);
        } catch (ErrorRecoveryException ex) {
            return null;
        } finally {
            current = block.end();
        }
    }

    private void reportExportUnlessTaken(IParserBlockHandler handler, BlockReader.Block block, int word) {
        Token token = tokens.get(word);
        if (block.prefixed().contains(word) && !handler.supportsExport(token.text())) {
            reportExport(token);
        }
    }

    private void reportExport(Token word) {
        diagnostics.reportError(Export.notSupportedBefore(word.text()),
                word.source().fileName(), word.source().lineNumber());
    }

    /**
     * Parses an expression, which can be a literal, a register, an identifier, or a vector. A
     * number, a component of a vector and the value of a typed literal may each be preceded by a
     * {@code -}, which makes the number negative.
     * @return The parsed {@link OperandNode} for the expression, or {@code null} after a
     *         reported error.
     */
    @Override
    public OperandNode expression() {
        int numberLength = numberLengthAt(current);
        if (numberLength > 0 && typeAt(current + numberLength) == TokenType.PIPE) {
            Token first = peek();
            List<Long> values = new ArrayList<>();
            values.add(signedNumber("Expected number component for vector."));
            while(match(TokenType.PIPE)) {
                values.add(signedNumber("Expected number component after '|'."));
            }
            return new LiteralNode(new Value.Vector(values), first.source());
        }

        if (check(TokenType.IDENTIFIER) && checkNext(TokenType.COLON)) {
            Token type = advance();
            advance();
            long value = signedNumber("Expected a number after the literal type.");
            return new LiteralNode(new Value.Typed(type.text(), value), type.source());
        }

        if (numberLength > 0) {
            Token num = peek();
            return new LiteralNode(new Value.Number(signedNumber("Expected a number.")), num.source());
        }

        if (match(TokenType.REGISTER)) {
            Token reg = previous();
            return new RegisterNode(reg.text(), reg.source());
        }

        if (match(TokenType.IDENTIFIER)) {
            Token identifier = previous();
            return new IdentifierNode(identifier.text(), identifier.source());
        }

        Token unexpected = advance();
        diagnostics.reportError("Expected a register, a literal, a vector or a name, but got '" + unexpected.text() + "'.", unexpected.source().fileName(), unexpected.source().lineNumber());
        return null;
    }

    /**
     * Tells how many tokens the number at the given position takes: two for a {@code -} followed
     * by a number, one for a number, none if no number stands there.
     * @param index The position in the token list.
     * @return The number of tokens, 0, 1 or 2.
     */
    private int numberLengthAt(int index) {
        return SignedNumber.at(tokens, index).map(SignedNumber::length).orElse(0);
    }

    private TokenType typeAt(int index) {
        return index < tokens.size() ? tokens.get(index).type() : TokenType.END_OF_FILE;
    }

    /**
     * Consumes a number, negated when a {@code -} precedes it, and the sign with it.
     * @param errorMessage The error reported when no number stands at the current position.
     * @return The value of the number.
     */
    private long signedNumber(String errorMessage) {
        Optional<SignedNumber> number = SignedNumber.at(tokens, current);
        if (number.isEmpty()) {
            // No number stands here, so this reports the error
            consume(TokenType.NUMBER, errorMessage);
        }
        for (int i = 0; i < number.orElseThrow().length(); i++) advance();
        return number.get().value();
    }

    private void synchronize() {
        advance();
        while (!isAtEnd()) {
            if (previous().type() == TokenType.NEWLINE) return;
            if (check(TokenType.DIRECTIVE) || check(TokenType.OPCODE)) return;
            advance();
        }
    }

    @Override
    public boolean match(TokenType... types) {
        for (TokenType type : types) {
            if (check(type)) {
                advance();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean check(TokenType type) {
        if (isAtEnd()) return false;
        return peek().type() == type;
    }

    @Override
    public boolean checkSymbol(String text) {
        return check(TokenType.SYMBOL) && peek().text().equals(text);
    }

    @Override
    public boolean matchSymbol(String text) {
        if (!checkSymbol(text)) return false;
        advance();
        return true;
    }

    /**
     * Checks the type of the next token without consuming it.
     * @param type The token type to check.
     * @return true if the next token is of the given type, false otherwise.
     */
    public boolean checkNext(TokenType type) {
        if (isAtEnd() || current + 1 >= tokens.size()) return false;
        return tokens.get(current + 1).type() == type;
    }

    @Override
    public Token advance() {
        if (!isAtEnd()) current++;
        return previous();
    }

    @Override
    public boolean isAtEnd() {
        return peek().type() == TokenType.END_OF_FILE;
    }

    @Override
    public void skipStatement() {
        while (!isAtEnd() && !check(TokenType.NEWLINE)) {
            advance();
        }
    }

    @Override
    public Token peek() {
        return tokens.get(current);
    }

    @Override
    public Token previous() {
        if (current == 0) return null;
        return tokens.get(current - 1);
    }

    @Override
    public Token consume(TokenType type, String errorMessage) {
        if (check(type)) return advance();
        Token unexpected = peek();
        diagnostics.reportError(errorMessage, unexpected.source().fileName(), unexpected.source().lineNumber());
        throw new ErrorRecoveryException(errorMessage);
    }

    @Override public String rootAliasChain() { return rootAliasChain; }
    @Override public Map<String, List<Token>> values() { return values; }

    /**
     * {@inheritDoc}
     * <p>
     * A parser of its own reads the tokens, with the same registry and a diagnostics engine of
     * its own, so that nothing it finds reaches this parser's reports.
     */
    @Override
    public Optional<OperandNode> operandOf(List<Token> operandTokens) {
        if (operandTokens.isEmpty() || operandTokens.getFirst().type() == TokenType.END_OF_FILE) {
            return Optional.empty();
        }
        Parser reader = new Parser(operandTokens, new DiagnosticsEngine(), statementRegistry);
        try {
            OperandNode operand = reader.expression();
            return operand != null && reader.isAtEnd() ? Optional.of(operand) : Optional.empty();
        } catch (ErrorRecoveryException incomplete) {
            return Optional.empty();
        }
    }

    @Override public DiagnosticsEngine getDiagnostics() { return diagnostics; }
    @Override public ParserState state() { return parserState; }
    @Override public boolean isExported() { return currentExported; }

    @Override
    public DirectiveLine lineOf(int index) {
        return DirectiveLine.of(tokens, index);
    }

    @Override
    public Token tokenAt(int index) {
        return tokens.get(index);
    }
}
