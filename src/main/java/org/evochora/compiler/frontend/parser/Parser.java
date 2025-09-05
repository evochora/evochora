package org.evochora.compiler.frontend.parser;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.directive.DirectiveHandlerRegistry;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.parser.ast.*;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.frontend.parser.features.label.LabelNode;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
    private final DirectiveHandlerRegistry directiveRegistry;
    private final Path basePath;
    private int current = 0;

    private final Deque<Map<String, Token>> registerAliasScopes = new ArrayDeque<>();
    private final Map<String, ProcedureNode> procedureTable = new HashMap<>();

    /**
     * Constructs a new Parser.
     * @param tokens The list of tokens to parse.
     * @param diagnostics The engine for reporting errors and warnings.
     * @param basePath The base path of the source file, used for resolving includes.
     */
    public Parser(List<Token> tokens, DiagnosticsEngine diagnostics, Path basePath) {
        this.tokens = tokens;
        this.diagnostics = diagnostics;
        this.basePath = basePath;
        this.directiveRegistry = DirectiveHandlerRegistry.initialize();
        registerAliasScopes.push(new HashMap<>());
    }

    /**
     * Pushes a new scope for register aliases onto the stack. Used for procedures and scopes.
     */
    public void pushRegisterAliasScope() {
        registerAliasScopes.push(new HashMap<>());
    }

    /**
     * Pops the current register alias scope from the stack.
     */
    public void popRegisterAliasScope() {
        if (registerAliasScopes.size() > 1) {
            registerAliasScopes.pop();
        }
    }

    /**
     * Adds a new register alias to the current scope.
     * @param name The alias name.
     * @param registerToken The token representing the actual register.
     */
    public void addRegisterAlias(String name, Token registerToken) {
        registerAliasScopes.peek().put(name.toUpperCase(), registerToken);
    }

    private Token resolveRegisterAlias(String name) {
        for (Map<String, Token> scope : registerAliasScopes) {
            Token token = scope.get(name.toUpperCase());
            if (token != null) {
                return token;
            }
        }
        return null;
    }

    /**
     * Gets the global register aliases.
     * @return A map of global register aliases.
     */
    public Map<String, Token> getGlobalRegisterAliases() {
        return new HashMap<>(registerAliasScopes.getLast()); // Global scope is the last element
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
        Optional<IDirectiveHandler> handlerOptional = directiveRegistry.get(directiveToken.text());

        if (handlerOptional.isPresent()) {
            IDirectiveHandler handler = handlerOptional.get();
            if (handler.getPhase() == CompilerPhase.PARSING) {
                return handler.parse(this);
            }
            advance();
            return null;
        } else {
            diagnostics.reportError("Unknown directive: " + directiveToken.text(), directiveToken.fileName(), directiveToken.line());
            advance();
            return null;
        }
    }

    private AstNode statement() {
        if (check(TokenType.IDENTIFIER) && checkNext(TokenType.COLON)) {
            Token labelToken = advance();
            advance();
            return new LabelNode(labelToken, declaration());
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
            return new InstructionNode(opcode, arguments);
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
            return new InstructionNode(opcode, arguments, refArguments, valArguments);
        } else {
            // Old syntax: CALL proc [WITH] arg1, arg2, ...
            while (!isAtEnd() && !check(TokenType.NEWLINE)) {
                arguments.add(expression());
            }
            return new InstructionNode(opcode, arguments);
        }
    }

    /**
     * Parses an expression, which can be a literal, a register, an identifier, or a vector.
     * @return The parsed {@link AstNode} for the expression.
     */
    public AstNode expression() {
        if (check(TokenType.NUMBER) && checkNext(TokenType.PIPE)) {
            List<Token> components = new ArrayList<>();
            components.add(consume(TokenType.NUMBER, "Expected number component for vector."));
            while(match(TokenType.PIPE)) {
                components.add(consume(TokenType.NUMBER, "Expected number component after '|'."));
            }
            return new VectorLiteralNode(components);
        }

        if (check(TokenType.IDENTIFIER) && checkNext(TokenType.COLON)) {
            Token type = advance();
            advance();
            Token value = consume(TokenType.NUMBER, "Expected a number after the literal type.");
            return new TypedLiteralNode(type, value);
        }

        if (match(TokenType.NUMBER)) return new NumberLiteralNode(previous());

        if (match(TokenType.REGISTER)) {
            Token reg = previous();
            return new RegisterNode(reg.text(), new SourceInfo(reg.fileName(), reg.line(), reg.column()), reg);
        }

        if (match(TokenType.IDENTIFIER)) {
            Token identifier = previous();
            return new IdentifierNode(identifier);
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
    @Override public void injectTokens(List<Token> tokens, int tokensToRemove) { throw new UnsupportedOperationException("Not supported in parsing phase."); }

    @Override
    public Path getBasePath() {
        return this.basePath;
    }

    @Override public boolean hasAlreadyIncluded(String path) { throw new UnsupportedOperationException("Not supported in parsing phase."); }
    @Override public void markAsIncluded(String path) { throw new UnsupportedOperationException("Not supported in parsing phase."); }
}