package org.evochora.compiler.frontend.preprocessor.features.macro;

import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext; // NEU

import java.util.ArrayList;
import java.util.List;

public class MacroDirectiveHandler implements IDirectiveHandler {
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
        context.advance(); // .MACRO konsumieren

        Token name = context.consume(TokenType.IDENTIFIER, "Expected macro name.");

        List<Token> params = new ArrayList<>();
        while (!context.isAtEnd() && context.peek().type() != TokenType.NEWLINE) {
            params.add(context.consume(TokenType.IDENTIFIER, "Expected parameter name."));
        }
        context.consume(TokenType.NEWLINE, "Expected newline after macro definition.");

        List<Token> body = new ArrayList<>();
        while (!context.isAtEnd() && !(context.peek().type() == TokenType.DIRECTIVE && context.peek().text().equalsIgnoreCase(".ENDM"))) {
            body.add(context.advance());
        }
        context.consume(TokenType.DIRECTIVE, "Expected .ENDM to close macro definition.");
        context.match(TokenType.NEWLINE);

        // BENUTZE DEN NEUEN KONTEXT
        ppContext.registerMacro(new MacroDefinition(name, params, body));

        int endIndex = preProcessor.getCurrentIndex();
        // Entferne den gesamten .MACRO...ENDM Block
        preProcessor.removeTokens(startIndex, endIndex - startIndex);
    }
}