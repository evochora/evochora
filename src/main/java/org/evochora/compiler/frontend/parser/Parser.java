package org.evochora.compiler.frontend.parser;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.frontend.directive.DirectiveHandlerRegistry;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.parser.ast.*;
import org.evochora.compiler.frontend.parser.features.label.LabelNode;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;
import org.evochora.compiler.frontend.parser.ast.RegisterNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class Parser implements ParsingContext {

    private final List<Token> tokens;
    private final DiagnosticsEngine diagnostics;
    private final DirectiveHandlerRegistry directiveRegistry;
    private int current = 0;

    private final Map<String, Token> registerAliasTable = new HashMap<>();
    private final Map<String, ProcedureNode> procedureTable = new HashMap<>();

    public Parser(List<Token> tokens, DiagnosticsEngine diagnostics) {
        this.tokens = tokens;
        this.diagnostics = diagnostics;
        this.directiveRegistry = DirectiveHandlerRegistry.initialize();
    }

    public List<AstNode> parse() {
        List<AstNode> statements = new ArrayList<>();
        while (!isAtEnd()) {
            // Leere Zeilen (nur Newlines) werden hier übersprungen.
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

    public AstNode declaration() {
        try {
            // Leere Zeilen vor einem Statement konsumieren
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
            advance(); // Ignoriere Direktiven für andere Phasen
            return null;
        } else {
            diagnostics.reportError("Unknown directive: " + directiveToken.text(), "Unknown", directiveToken.line());
            advance();
            return null;
        }
    }

    private AstNode statement() {
        if (check(TokenType.IDENTIFIER) && checkNext(TokenType.COLON)) {
            Token labelToken = advance();
            advance(); // ':' konsumieren
            return new LabelNode(labelToken, declaration());
        }
        return instructionStatement();
    }

    private AstNode instructionStatement() {
        if (match(TokenType.OPCODE)) {
            Token opcode = previous();
            List<AstNode> arguments = new ArrayList<>();
            while (!isAtEnd() && !check(TokenType.NEWLINE)) {
                arguments.add(expression());
            }
            return new InstructionNode(opcode, arguments);
        }

        Token unexpected = advance();
        if (unexpected.type() != TokenType.END_OF_FILE && unexpected.type() != TokenType.NEWLINE) {
            diagnostics.reportError("Expected instruction or directive, but got '" + unexpected.text() + "'.", "Unknown", unexpected.line());
        }
        return null;
    }

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
            advance(); // ':' konsumieren
            Token value = consume(TokenType.NUMBER, "Expected a number after the literal type.");
            return new TypedLiteralNode(type, value);
        }

        if (match(TokenType.NUMBER)) return new NumberLiteralNode(previous());
        if (match(TokenType.REGISTER)) {
            Token reg = previous();
            String name = reg.text().toUpperCase();
            if (registerAliasTable.containsKey(name)) {
                return new RegisterNode(registerAliasTable.get(name));
            }
            return new RegisterNode(reg);
        }

        if (match(TokenType.IDENTIFIER)) {
            Token identifier = previous();
            String name = identifier.text().toUpperCase();
            if (registerAliasTable.containsKey(name)) {
                return new RegisterNode(registerAliasTable.get(name));
            }
            return new IdentifierNode(identifier);
        }

        Token unexpected = advance();
        diagnostics.reportError("Unexpected token while parsing expression: " + unexpected.text(), "Unknown", unexpected.line());
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
        diagnostics.reportError(errorMessage, "Unknown", unexpected.line());
        throw new RuntimeException(errorMessage);
    }

    public Map<String, Token> getRegisterAliasTable() { return registerAliasTable; }
    public void registerProcedure(ProcedureNode procedure) {
        String name = procedure.name().text().toUpperCase();
        if (procedureTable.containsKey(name)) {
            getDiagnostics().reportError("Procedure '" + name + "' is already defined.", "Unknown", procedure.name().line());
        } else {
            procedureTable.put(name, procedure);
        }
    }
    public Map<String, ProcedureNode> getProcedureTable() { return procedureTable; }
    @Override public DiagnosticsEngine getDiagnostics() { return diagnostics; }
    @Override public void injectTokens(List<Token> tokens, int tokensToRemove) { throw new UnsupportedOperationException("Not supported in parsing phase."); }
    @Override public Path getBasePath() { throw new UnsupportedOperationException("Not supported in parsing phase."); }
    @Override public boolean hasAlreadyIncluded(String path) { throw new UnsupportedOperationException("Not supported in parsing phase."); }
    @Override public void markAsIncluded(String path) { throw new UnsupportedOperationException("Not supported in parsing phase."); }
}