package org.evochora.compiler.frontend.parser;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.diagnostics.ErrorRecoveryException;
import org.evochora.compiler.frontend.BlockKind;
import org.evochora.compiler.frontend.BlockReader;
import org.evochora.compiler.frontend.DirectiveLine;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IdentifierNode;

import org.evochora.compiler.model.ast.NumberLiteralNode;
import org.evochora.compiler.model.ast.OperandNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.model.ast.TypedLiteralNode;
import org.evochora.compiler.model.ast.VectorLiteralNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The main parser for the assembly language. It consumes a list of tokens
 * from the {@link org.evochora.compiler.frontend.lexer.Lexer} and produces an Abstract Syntax Tree (AST).
 * All statement dispatch goes through the {@link ParserStatementRegistry}. A statement that
 * opens a registered kind of block is read as a block first, by the rules of
 * {@link BlockReader}, and its handler is called with the block only when the block is whole.
 */
public class Parser implements IParsingContext {

    private final List<Token> tokens;
    private final DiagnosticsEngine diagnostics;
    private final ParserStatementRegistry statementRegistry;
    private final BlockReader blockReader;
    private int current = 0;

    private final ParserState parserState = new ParserState();
    private boolean currentExported = false;

    /**
     * Constructs a new Parser.
     * @param tokens The list of tokens to parse.
     * @param diagnostics The engine for reporting errors and warnings.
     * @param statementRegistry The pre-built registry of statement handlers.
     */
    public Parser(List<Token> tokens, DiagnosticsEngine diagnostics,
                  ParserStatementRegistry statementRegistry) {
        this.tokens = tokens;
        this.diagnostics = diagnostics;
        this.statementRegistry = statementRegistry;
        this.blockReader = new BlockReader(statementRegistry::blockKindOf, false, Parser::isExport, diagnostics);
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
            if (isExport(peek())) {
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

            // A closer or divider the walk reaches stands outside any block: the handler of a
            // block consumes the block's closer and dividers with it.
            BlockKind kind = statementRegistry.blockKindOf(keyword.text()).orElse(null);
            if (kind != null) {
                diagnostics.reportError(keyword.text() + (kind.isCloser(keyword.text()) ? " closes" : " divides")
                        + " no open block", keyword.source().fileName(), keyword.source().lineNumber());
                while (!isAtEnd() && advance().type() != TokenType.NEWLINE) {
                    // The word's line goes with it.
                }
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
        diagnostics.reportError("EXPORT is not supported before '" + word.text() + "'.",
                word.source().fileName(), word.source().lineNumber());
    }

    private static boolean isExport(Token token) {
        return token.type() == TokenType.IDENTIFIER && "EXPORT".equalsIgnoreCase(token.text());
    }

    /**
     * Parses an expression, which can be a literal, a register, an identifier, or a vector.
     * @return The parsed {@link OperandNode} for the expression, or {@code null} after a
     *         reported error.
     */
    @Override
    public OperandNode expression() {
        if (check(TokenType.NUMBER) && checkNext(TokenType.PIPE)) {
            Token first = consume(TokenType.NUMBER, "Expected number component for vector.");
            List<Integer> values = new ArrayList<>();
            values.add((int) first.value());
            while(match(TokenType.PIPE)) {
                Token comp = consume(TokenType.NUMBER, "Expected number component after '|'.");
                values.add((int) comp.value());
            }
            return new VectorLiteralNode(java.util.Collections.unmodifiableList(values),
                    first.source());
        }

        if (check(TokenType.IDENTIFIER) && checkNext(TokenType.COLON)) {
            Token type = advance();
            advance();
            Token valueTok = consume(TokenType.NUMBER, "Expected a number after the literal type.");
            return new TypedLiteralNode(type.text(), (int) valueTok.value(),
                    type.source());
        }

        if (match(TokenType.NUMBER)) {
            Token num = previous();
            return new NumberLiteralNode((int) num.value(), num.source());
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

    @Override public DiagnosticsEngine getDiagnostics() { return diagnostics; }
    @Override public ParserState state() { return parserState; }
    @Override public boolean isExported() { return currentExported; }

    @Override
    public DirectiveLine currentLine(Predicate<Token> passedOver) {
        return DirectiveLine.of(tokens, current, passedOver);
    }

    @Override
    public DirectiveLine lineOf(int index) {
        return DirectiveLine.of(tokens, index);
    }

    @Override
    public Token tokenAt(int index) {
        return tokens.get(index);
    }
}
