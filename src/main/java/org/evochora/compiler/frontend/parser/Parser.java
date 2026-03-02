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
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.frontend.parser.features.label.LabelNode;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The main parser for the assembly language. It consumes a list of tokens
 * from the {@link org.evochora.compiler.frontend.lexer.Lexer} and produces an Abstract Syntax Tree (AST).
 * The parser is also responsible for handling directives and managing scopes.
 */
public class Parser implements ParsingContext {

    private final List<Token> tokens;
    private final DiagnosticsEngine diagnostics;
    private final ParserDirectiveRegistry directiveRegistry;
    private int current = 0;

    private final ParserState parserState = new ParserState();
    private final Map<String, ProcedureNode> procedureTable = new HashMap<>();

    /**
     * Constructs a new Parser.
     * @param tokens The list of tokens to parse.
     * @param diagnostics The engine for reporting errors and warnings.
     */
    public Parser(List<Token> tokens, DiagnosticsEngine diagnostics) {
        this.tokens = tokens;
        this.diagnostics = diagnostics;
        this.directiveRegistry = ParserDirectiveRegistry.initialize();
    }

    /**
     * Gets the global register aliases.
     * @return A map of global register aliases.
     */
    public Map<String, Token> getGlobalRegisterAliases() {
        RegisterAliasState aliases = parserState.get(RegisterAliasState.class);
        return aliases != null ? aliases.getGlobalAliases() : new HashMap<>();
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
     * Parses a single declaration, which can be a directive or a statement.
     * @return The parsed {@link AstNode}, or null if an error occurs.
     */
    @Override
    public AstNode declaration() {
        try {
            while (check(TokenType.NEWLINE)) {
                advance();
            }
            if (isAtEnd()) return null;

            if (check(TokenType.DIRECTIVE)) {
                return directiveStatement();
            }
            return statement();
        } catch (RuntimeException ex) {
            synchronize();
            return null;
        }
    }

    private AstNode directiveStatement() {
        Token directiveToken = peek();
        Optional<IParserDirectiveHandler> handlerOptional = directiveRegistry.get(directiveToken.text());

        if (handlerOptional.isPresent()) {
            return handlerOptional.get().parse(this);
        } else {
            // Directive not registered in the parser (e.g., preprocessing-only directives)
            advance();
            return null;
        }
    }

    private AstNode statement() {
        if (check(TokenType.IDENTIFIER) && checkNext(TokenType.COLON)) {
            Token labelToken = advance();
            advance(); // consume ':'
            boolean exported = false;
            if (check(TokenType.IDENTIFIER) && "EXPORT".equalsIgnoreCase(peek().text())) {
                advance(); // consume 'EXPORT'
                exported = true;
            }
            return new LabelNode(labelToken, declaration(), exported);
        }
        return instructionStatement();
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
            return new InstructionNode(opcode.text(), arguments,
                    new SourceInfo(opcode.fileName(), opcode.line(), opcode.column()));
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
            SourceInfo si = new SourceInfo(opcode.fileName(), opcode.line(), opcode.column());
            return new InstructionNode(opcode.text(), arguments, refArguments, valArguments, si);
        } else {
            // Old syntax: CALL proc [WITH] arg1, arg2, ...
            while (!isAtEnd() && !check(TokenType.NEWLINE)) {
                arguments.add(expression());
            }
            return new InstructionNode(opcode.text(), arguments,
                    new SourceInfo(opcode.fileName(), opcode.line(), opcode.column()));
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
                    new SourceInfo(first.fileName(), first.line(), first.column()));
        }

        if (check(TokenType.IDENTIFIER) && checkNext(TokenType.COLON)) {
            Token type = advance();
            advance();
            Token valueTok = consume(TokenType.NUMBER, "Expected a number after the literal type.");
            return new TypedLiteralNode(type.text(), (int) valueTok.value(),
                    new SourceInfo(type.fileName(), type.line(), type.column()));
        }

        if (match(TokenType.NUMBER)) {
            Token num = previous();
            return new NumberLiteralNode((int) num.value(), new SourceInfo(num.fileName(), num.line(), num.column()));
        }

        if (match(TokenType.REGISTER)) {
            Token reg = previous();
            return new RegisterNode(reg.text(), new SourceInfo(reg.fileName(), reg.line(), reg.column()));
        }

        if (match(TokenType.IDENTIFIER)) {
            Token identifier = previous();
            return new IdentifierNode(identifier.text(), new SourceInfo(identifier.fileName(), identifier.line(), identifier.column()));
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

    /**
     * Registers a new procedure in the parser's procedure table.
     * @param procedure The procedure node to register.
     */
    public void registerProcedure(ProcedureNode procedure) {
        String name = procedure.name().text().toUpperCase();
        if (procedureTable.containsKey(name)) {
            getDiagnostics().reportError("Procedure '" + name + "' is already defined.", procedure.name().fileName(), procedure.name().line());
        } else {
            procedureTable.put(name, procedure);
        }
    }

    /**
     * Gets the table of defined procedures.
     * @return The procedure table.
     */
    public Map<String, ProcedureNode> getProcedureTable() { return procedureTable; }
    @Override public DiagnosticsEngine getDiagnostics() { return diagnostics; }
    @Override public ParserState state() { return parserState; }
}