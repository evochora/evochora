package org.evochora.compiler.frontend.preprocessor.features.routine;

import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.ParsingContext;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext; // NEU

import java.util.ArrayList;
import java.util.List;

public class RoutineDirectiveHandler implements IDirectiveHandler {
    @Override
    public CompilerPhase getPhase() {
        return CompilerPhase.PREPROCESSING;
    }

    // Alte parse-Methode löschen oder auskommentieren
    @Override
    public AstNode parse(ParsingContext context) { return null; }

    @Override
    public void parse(ParsingContext context, PreProcessorContext ppContext) {
        PreProcessor preProcessor = (PreProcessor) context;
        int startIndex = preProcessor.getCurrentIndex();
        context.advance(); // .ROUTINE konsumieren

        Token name = context.consume(TokenType.IDENTIFIER, "Expected routine name.");

        List<Token> params = new ArrayList<>();
        while (!context.isAtEnd() && context.peek().type() != TokenType.NEWLINE) {
            params.add(context.consume(TokenType.IDENTIFIER, "Expected parameter name."));
        }
        context.consume(TokenType.NEWLINE, "Expected newline after routine definition.");

        List<Token> body = new ArrayList<>();
        while (!context.isAtEnd() && !(context.peek().type() == TokenType.DIRECTIVE && context.peek().text().equalsIgnoreCase(".ENDR"))) {
            body.add(context.advance());
        }
        context.consume(TokenType.DIRECTIVE, "Expected .ENDR to close routine definition.");
        context.match(TokenType.NEWLINE);

        // BENUTZE DEN NEUEN KONTEXT
        ppContext.registerRoutine(new RoutineDefinition(name, params, body));

        int endIndex = preProcessor.getCurrentIndex();
        // Entferne den gesamten .ROUTINE...ENDR Block
        preProcessor.removeTokens(startIndex, endIndex - startIndex);
    }
}