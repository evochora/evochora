package org.evochora.compiler.core;

import org.evochora.compiler.core.ast.*;
import org.evochora.compiler.core.directives.DirectiveHandlerRegistry;
import org.evochora.compiler.core.directives.IDirectiveHandler;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Der Parser nimmt eine Liste von Tokens vom {@link Lexer} entgegen und
 * versucht, daraus eine strukturierte Repräsentation des Programms zu erstellen,
 * typischerweise einen Abstract Syntax Tree (AST).
 */
public class Parser {

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
            // Überspringe leere Zeilen
            if (match(TokenType.NEWLINE)) return null;

            // Explizit geschriebene Version des Lambda-Ausdrucks

            if (check(TokenType.DIRECTIVE)) {
                Token directiveToken = peek();

                // 1. Hole den Handler aus der Registry. Das ist ein Optional<IDirectiveHandler>.
                Optional<IDirectiveHandler> handlerOptional = directiveRegistry.get(directiveToken.text());

                // 2. Wenn der Handler existiert (das .map wird ausgeführt)...
                if (handlerOptional.isPresent()) {
                    IDirectiveHandler handler = handlerOptional.get();
                    // ...rufe seine parse-Methode auf.
                    // FÜR .DEFINE gibt diese Methode 'null' zurück, was OK ist.
                    return handler.parse(this);
                }
                // 3. Wenn der Handler NICHT existiert (das .orElseGet wird ausgeführt)...
                else {
                    // ...melde einen Fehler und gib null zurück.
                    diagnostics.reportError("Unknown directive: " + directiveToken.text(), "Unknown", directiveToken.line());
                    advance(); // Überspringe die unbekannte Direktive
                    return null;
                }
            }
            return statement();
        } catch (Exception ex) {
            synchronize();
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
            // Solange wir nicht am Zeilenende oder Dateiende sind, parsen wir Argumente.
            while (!isAtEnd() && !check(TokenType.NEWLINE)) {
                arguments.add(expression());
            }
            return new InstructionNode(opcode, arguments);
        }

        // Wenn wir hier ankommen, aber nicht am Ende sind, ist es ein Fehler.
        if (!isAtEnd()) {
            Token unexpected = advance();
            diagnostics.reportError("Expected instruction, but got " + unexpected.text(), "Unknown", unexpected.line());
        }
        return null;
    }

    /**
     * Parst einen Ausdruck (z.B. ein Argument einer Instruktion).
     */
    public AstNode expression() {
        // Prüfe auf typisiertes Literal: IDENTIFIER:NUMBER
        if (check(TokenType.IDENTIFIER) && checkNext(TokenType.COLON)) {
            Token type = advance();
            advance(); // ':' konsumieren
            Token value = consume(TokenType.NUMBER, "Expected a number after the literal type.");
            return new TypedLiteralNode(type, value);
        }

        // Prüfe auf Vektor-Literal: NUMBER | NUMBER ...
        if (check(TokenType.NUMBER) && checkNext(TokenType.PIPE)) {
            List<Token> components = new ArrayList<>();
            components.add(consume(TokenType.NUMBER, "Expected number component for vector."));
            while(match(TokenType.PIPE)) {
                components.add(consume(TokenType.NUMBER, "Expected number component after '|'."));
            }
            return new VectorLiteralNode(components);
        }

        if (match(TokenType.NUMBER)) return new NumberLiteralNode(previous());
        if (match(TokenType.REGISTER)) return new RegisterNode(previous());

        if (match(TokenType.IDENTIFIER)) {
            Token identifier = previous();
            String name = identifier.text().toUpperCase();

            // Prüfe, ob es ein Register-Alias ist
            if (registerAliasTable.containsKey(name)) {
                return new RegisterNode(registerAliasTable.get(name));
            }
            // Prüfe, ob es eine definierte Konstante ist
            if (symbolTable.containsKey(name)) {
                return new NumberLiteralNode(symbolTable.get(name));
            }
            // Ansonsten ist es ein unbekannter Identifier (z.B. ein Label)
            return new IdentifierNode(identifier);
        }

        Token unexpected = advance();
        diagnostics.reportError("Unexpected token while parsing expression: " + unexpected.text(), "Unknown", unexpected.line());
        return null;
    }

    /**
     * Setzt den Parser nach einem Fehler zurück, um das Parsing fortzusetzen.
     * Überspringt Tokens, bis es einen wahrscheinlichen Start eines neuen Statements findet.
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
        throw new RuntimeException(errorMessage); // Wirft eine interne Exception für die Synchronisation
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
}