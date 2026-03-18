package org.evochora.compiler.frontend.parser;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.InstructionNode;
import org.evochora.compiler.model.ast.NumberLiteralNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.model.ast.TypedLiteralNode;
import org.evochora.compiler.model.ast.VectorLiteralNode;
import org.evochora.compiler.features.label.LabelNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The main parser for the assembly language. It consumes a list of tokens
 * from the {@link org.evochora.compiler.frontend.lexer.Lexer} and produces an Abstract Syntax Tree (AST).
 * All statement dispatch goes through the {@link ParserStatementRegistry}.
 */
public class Parser implements ParsingContext {

    private final List<Token> tokens;
    private final DiagnosticsEngine diagnostics;
    private final ParserStatementRegistry statementRegistry;
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
    }

    /**
     * Parses the entire token stream and returns a list of top-level AST nodes.
     * @return A list of parsed {@link AstNode}s.
     */
    public List<AstNode> parse() {
        List<AstNode> statements = new ArrayList<>();
        while (!isAtEnd()) {
            if (match(TokenType.NEWLINE)) {
                continue;
            }
            AstNode statement = declaration();
            if (statement != null) {
                statements.add(statement);
            }
        }
        return statements;
    }

    /**
     * Parses a single declaration. Handles EXPORT keyword, then dispatches
     * through the statement registry by keyword. Label syntax and generic
     * instructions are handled as fallbacks.
     * @return The parsed {@link AstNode}, or null if an error occurs.
     */
    @Override
    public AstNode declaration() {
        try {
            while (check(TokenType.NEWLINE)) {
                advance();
            }
            if (isAtEnd()) return null;

            currentExported = false;
            if (check(TokenType.IDENTIFIER) && "EXPORT".equalsIgnoreCase(peek().text())) {
                currentExported = true;
                advance();
            }

            // Label syntax: IDENTIFIER COLON (will be moved to preprocessor in D13e)
            if (check(TokenType.IDENTIFIER) && checkNext(TokenType.COLON)) {
                Token labelToken = advance();
                advance(); // consume ':'
                boolean exported = currentExported;
                return new LabelNode(labelToken.text(), labelToken.toSourceInfo(), declaration(), exported);
            }

            // Keyword lookup in statement registry (directives, opcodes, etc.)
            Token keyword = peek();
            Optional<IParserStatementHandler> handler = statementRegistry.get(keyword.text());
            if (handler.isPresent()) {
                return handler.get().parse(this);
            }

            // After preprocessing, all remaining directives must have a registered handler
            if (check(TokenType.DIRECTIVE)) {
                Token directive = advance();
                diagnostics.reportError(
                        "Unregistered directive '" + directive.text() + "'.",
                        directive.fileName(), directive.line());
                return null;
            }

            // EXPORT only valid before label or registered keyword
            if (currentExported) {
                Token errorToken = isAtEnd() ? previous() : peek();
                diagnostics.reportError(
                        "EXPORT can only precede a label definition or a directive (.PROC, .IMPORT, .DEFINE).",
                        errorToken.fileName(), errorToken.line());
            }

            // Generic instructions (will be moved to default handler in D14)
            return instructionStatement();
        } catch (RuntimeException ex) {
            synchronize();
            return null;
        }
    }

    private AstNode instructionStatement() {
        if (match(TokenType.OPCODE)) {
            Token opcode = previous();
            if ("CALL".equalsIgnoreCase(opcode.text())) {
                return parseCallInstruction(opcode);
            }
            List<AstNode> arguments = new ArrayList<>();
            while (!isAtEnd() && !check(TokenType.NEWLINE)) {
                arguments.add(expression());
            }
            return new InstructionNode(opcode.text(), arguments, opcode.toSourceInfo());
        }

        Token unexpected = advance();
        if (unexpected.type() != TokenType.END_OF_FILE && unexpected.type() != TokenType.NEWLINE) {
            diagnostics.reportError("Expected instruction or directive, but got '" + unexpected.text() + "'.", unexpected.fileName(), unexpected.line());
        }
        return null;
    }

    private InstructionNode parseCallInstruction(Token opcode) {
        AstNode procName = expression();

        List<AstNode> arguments = new ArrayList<>();
        arguments.add(procName);

        // Check for new syntax (REF/VAL)
        if (check(TokenType.IDENTIFIER) && ("REF".equalsIgnoreCase(peek().text()) || "VAL".equalsIgnoreCase(peek().text()))) {
            List<AstNode> refArguments = new ArrayList<>();
            List<AstNode> valArguments = new ArrayList<>();
            boolean refParsed = false;
            boolean valParsed = false;

            while (!isAtEnd() && !check(TokenType.NEWLINE)) {
                if (!refParsed && check(TokenType.IDENTIFIER) && "REF".equalsIgnoreCase(peek().text())) {
                    advance(); // Consume REF
                    refParsed = true;
                    while (!isAtEnd() && !check(TokenType.NEWLINE) && !(check(TokenType.IDENTIFIER) && "VAL".equalsIgnoreCase(peek().text()))) {
                        refArguments.add(expression());
                    }
                } else if (!valParsed && check(TokenType.IDENTIFIER) && "VAL".equalsIgnoreCase(peek().text())) {
                    advance(); // Consume VAL
                    valParsed = true;
                    while (!isAtEnd() && !check(TokenType.NEWLINE) && !(check(TokenType.IDENTIFIER) && "REF".equalsIgnoreCase(peek().text()))) {
                        valArguments.add(expression());
                    }
                } else {
                    Token unexpected = advance();
                    diagnostics.reportError("Unexpected token '" + unexpected.text() + "' in CALL statement. Expected REF, VAL, or newline.", unexpected.fileName(), unexpected.line());
                    break;
                }
            }
            return new InstructionNode(opcode.text(), arguments, refArguments, valArguments, opcode.toSourceInfo());
        } else {
            // Old syntax: CALL proc [WITH] arg1, arg2, ...
            while (!isAtEnd() && !check(TokenType.NEWLINE)) {
                arguments.add(expression());
            }
            return new InstructionNode(opcode.text(), arguments, opcode.toSourceInfo());
        }
    }

    /**
     * Parses an expression, which can be a literal, a register, an identifier, or a vector.
     * @return The parsed {@link AstNode} for the expression.
     */
    @Override
    public AstNode expression() {
        if (check(TokenType.NUMBER) && checkNext(TokenType.PIPE)) {
            Token first = consume(TokenType.NUMBER, "Expected number component for vector.");
            List<Integer> values = new ArrayList<>();
            values.add((int) first.value());
            while(match(TokenType.PIPE)) {
                Token comp = consume(TokenType.NUMBER, "Expected number component after '|'.");
                values.add((int) comp.value());
            }
            return new VectorLiteralNode(java.util.Collections.unmodifiableList(values),
                    first.toSourceInfo());
        }

        if (check(TokenType.IDENTIFIER) && checkNext(TokenType.COLON)) {
            Token type = advance();
            advance();
            Token valueTok = consume(TokenType.NUMBER, "Expected a number after the literal type.");
            return new TypedLiteralNode(type.text(), (int) valueTok.value(),
                    type.toSourceInfo());
        }

        if (match(TokenType.NUMBER)) {
            Token num = previous();
            return new NumberLiteralNode((int) num.value(), num.toSourceInfo());
        }

        if (match(TokenType.REGISTER)) {
            Token reg = previous();
            return new RegisterNode(reg.text(), reg.toSourceInfo());
        }

        if (match(TokenType.IDENTIFIER)) {
            Token identifier = previous();
            return new IdentifierNode(identifier.text(), identifier.toSourceInfo());
        }

        Token unexpected = advance();
        diagnostics.reportError("Unexpected token while parsing expression: " + unexpected.text(), unexpected.fileName(), unexpected.line());
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
        return tokens.get(current - 1);
    }

    @Override
    public Token consume(TokenType type, String errorMessage) {
        if (check(type)) return advance();
        Token unexpected = peek();
        diagnostics.reportError(errorMessage, unexpected.fileName(), unexpected.line());
        throw new RuntimeException(errorMessage);
    }

    @Override public DiagnosticsEngine getDiagnostics() { return diagnostics; }
    @Override public ParserState state() { return parserState; }
    @Override public boolean isExported() { return currentExported; }
}
