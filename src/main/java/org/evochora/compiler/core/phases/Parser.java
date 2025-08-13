package org.evochora.compiler.core.phases;

import org.evochora.compiler.core.Token;
import org.evochora.compiler.core.TokenType;
import org.evochora.compiler.core.ast.*;
import org.evochora.compiler.core.directives.DirectiveHandlerRegistry;
import org.evochora.compiler.core.directives.IDirectiveHandler;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;

import java.util.ArrayList;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Der Parser nimmt eine Liste von Tokens vom {@link Lexer} entgegen und
 * versucht, daraus eine strukturierte Repräsentation des Programms zu erstellen,
 * typischerweise einen Abstract Syntax Tree (AST).
 */
public class Parser implements ParsingContext {

    private final List<Token> tokens;
    private final DiagnosticsEngine diagnostics;
    private final DirectiveHandlerRegistry directiveRegistry;
    private int current = 0;

    /**
     * TODO: [Phase 4] Dies ist eine temporäre, einfache Symboltabelle.
     *  In einer späteren Phase wird sie durch eine richtige SymbolTable-Klasse
     *  ersetzt, die Geltungsbereiche (Scopes) verwalten kann.
     */
    private final Map<String, Token> symbolTable = new HashMap<>();
    private final Map<String, Token> registerAliasTable = new HashMap<>();
    private final Map<String, ProcedureNode> procedureTable = new HashMap<>();

    /**
     * Erstellt einen neuen Parser.
     * @param tokens Die vom Lexer erzeugte Liste von Tokens.
     * @param diagnostics Die Engine zum Melden von Fehlern.
     */
    public Parser(List<Token> tokens, DiagnosticsEngine diagnostics) {
        this.tokens = tokens;
        this.diagnostics = diagnostics;
        this.directiveRegistry = DirectiveHandlerRegistry.initialize();
    }

    /**
     * Führt das Parsing der gesamten Token-Liste durch.
     * @return Eine Liste von AST-Knoten, die die Top-Level-Statements des Programms repräsentieren.
     */
    public List<AstNode> parse() {
        List<AstNode> statements = new ArrayList<>();
        while (!isAtEnd()) {
            AstNode statement = declaration();
            if (statement != null) {
                statements.add(statement);
            }
            // Wenn declaration() null zurückgibt (z.B. eine leere Zeile),
            // müssen wir sicherstellen, dass wir trotzdem vorankommen, um eine Endlosschleife zu vermeiden.
            else if (!isAtEnd()){
                 // Dies geschieht bereits durch match() in declaration(), aber als Sicherheitsnetz.
                 // In den meisten Fällen wird dies nicht benötigt.
            }
        }
        return statements;
    }

    /**
     * Parst eine einzelne Top-Level-Deklaration oder ein Statement.
     * Dies ist die Haupt-Verteiler-Methode im Parser.
     * @return Ein AST-Knoten oder {@code null}, wenn eine leere Zeile geparst wurde.
     */
    public AstNode declaration() {
        try {
            if (match(TokenType.NEWLINE)) return null;

            if (check(TokenType.DIRECTIVE)) {
                return directiveStatement();
            }
            return statement();
        } catch (RuntimeException ex) {
            synchronize();
            return null;
        }
    }

    /**
     * Parst eine Direktive, indem es an den entsprechenden Handler delegiert.
     */
    private AstNode directiveStatement() {
        // Explizit geschriebene Version des Lambda-Ausdrucks
        Token directiveToken = peek();
        Optional<IDirectiveHandler> handlerOptional = directiveRegistry.get(directiveToken.text());

        if (handlerOptional.isPresent()) {
            IDirectiveHandler handler = handlerOptional.get();
            // Der Parser ruft nur Handler für die PARSING-Phase auf.
            if (handler.getPhase() == CompilerPhase.PARSING) {
                return handler.parse(this);
            }
            // Ignoriere Handler für andere Phasen (z.B. PRE_PROCESSING)
            advance();
            return null;
        } else {
            diagnostics.reportError("Unknown directive: " + directiveToken.text(), "Unknown", directiveToken.line());
            advance();
            return null;
        }
    }

    /**
     * Parst ein reguläres Statement (Label oder Instruktion).
     */
    private AstNode statement() {
        if (check(TokenType.IDENTIFIER) && checkNext(TokenType.COLON)) {
            Token labelToken = advance();
            advance(); // ':' konsumieren
            return new LabelNode(labelToken, statement());
        }
        return instructionStatement();
    }

    /**
     * Parst eine Instruktion und ihre Argumente.
     */
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
        diagnostics.reportError("Expected instruction, but got '" + unexpected.text() + "'.", "Unknown", unexpected.line());
        return null;
    }

    /**
     * Parst einen Ausdruck (z.B. ein Argument einer Instruktion).
     */
    public AstNode expression() {
        // Prüfe auf Vektor-Literal: NUMBER | NUMBER ...
        if (check(TokenType.NUMBER) && checkNext(TokenType.PIPE)) {
            List<Token> components = new ArrayList<>();
            components.add(consume(TokenType.NUMBER, "Expected number component for vector."));
            while(match(TokenType.PIPE)) {
                components.add(consume(TokenType.NUMBER, "Expected number component after '|'."));
            }
            return new VectorLiteralNode(components);
        }

        // Prüfe auf typisiertes Literal: IDENTIFIER:NUMBER
        if (check(TokenType.IDENTIFIER) && checkNext(TokenType.COLON)) {
            Token type = advance();
            advance(); // ':' konsumieren
            Token value = consume(TokenType.NUMBER, "Expected a number after the literal type.");
            return new TypedLiteralNode(type, value);
        }

        if (match(TokenType.NUMBER)) return new NumberLiteralNode(previous());
        if (match(TokenType.REGISTER)) return new RegisterNode(previous());

        if (match(TokenType.IDENTIFIER)) {
            Token identifier = previous();
            String name = identifier.text().toUpperCase();

            if (registerAliasTable.containsKey(name)) return new RegisterNode(registerAliasTable.get(name));
            if (symbolTable.containsKey(name)) return new NumberLiteralNode(symbolTable.get(name));
            return new IdentifierNode(identifier);
        }

        Token unexpected = advance();
        diagnostics.reportError("Unexpected token while parsing expression: " + unexpected.text(), "Unknown", unexpected.line());
        return null;
    }

    /**
     * Setzt den Parser nach einem Fehler zurück, um das Parsing fortzusetzen.
     */
    private void synchronize() {
        advance();
        while (!isAtEnd()) {
            if (previous().type() == TokenType.NEWLINE) return;
            switch (peek().type()) {
                case OPCODE, DIRECTIVE:
                    return;
            }
            advance();
        }
    }

    // --- Hilfsmethoden für Handler ---

    public boolean match(TokenType... types) {
        for (TokenType type : types) {
            if (check(type)) {
                advance();
                return true;
            }
        }
        return false;
    }

    public boolean check(TokenType type) {
        if (isAtEnd()) return false;
        return peek().type() == type;
    }

    public boolean checkNext(TokenType type) {
        if (isAtEnd() || tokens.get(current + 1).type() == TokenType.END_OF_FILE) {
            return false;
        }
        return tokens.get(current + 1).type() == type;
    }

    public Token advance() {
        if (!isAtEnd()) current++;
        return previous();
    }

    public boolean isAtEnd() {
        return peek().type() == TokenType.END_OF_FILE;
    }

    public Token peek() {
        return tokens.get(current);
    }

    public Token previous() {
        return tokens.get(current - 1);
    }

    public Token consume(TokenType type, String errorMessage) {
        if (check(type)) {
            return advance();
        }
        Token unexpected = peek();
        diagnostics.reportError(errorMessage, "Unknown", unexpected.line());
        throw new RuntimeException(errorMessage);
    }

    public Map<String, Token> getSymbolTable() {
        return symbolTable;
    }

    public Map<String, Token> getRegisterAliasTable() {
        return registerAliasTable;
    }

    public void registerProcedure(ProcedureNode procedure) {
        String name = procedure.name().text().toUpperCase();
        if (procedureTable.containsKey(name)) {
            getDiagnostics().reportError("Procedure '" + name + "' is already defined.", "Unknown", procedure.name().line());
        } else {
            procedureTable.put(name, procedure);
        }
    }

    public Map<String, ProcedureNode> getProcedureTable() {
        return procedureTable;
    }

    public DiagnosticsEngine getDiagnostics() {
        return diagnostics;
    }

    @Override
    public void injectTokens(List<Token> tokens, int tokensToRemove) {
        throw new UnsupportedOperationException("Token injection is not supported during the parsing phase.");
    }

    @Override
    public Path getBasePath() {
        throw new UnsupportedOperationException("Base path is not available during the parsing phase.");
    }

    @Override
    public boolean hasAlreadyIncluded(String path) {
        throw new UnsupportedOperationException("Inclusion tracking is not available during the parsing phase.");
    }

    @Override
    public void markAsIncluded(String path) {
        throw new UnsupportedOperationException("Inclusion tracking is not available during the parsing phase.");
    }
}