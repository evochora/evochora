package org.evochora.compiler.directives;

import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.junit.jupiter.api.Test;
import org.evochora.compiler.frontend.preprocessor.features.routine.RoutineDefinition;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

public class RoutineDirectiveTest {

    @Test
    void testRoutineDefinitionIsParsedAndRemoved() {
        // Arrange
        String source = String.join("\n",
                ".ROUTINE INCREMENT REG",
                "  ADDI REG DATA:1",
                ".ENDR",
                "SETI %DR0 DATA:0" // Dieser Code sollte übrig bleiben
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> initialTokens = lexer.scanTokens();
        PreProcessor preProcessor = new PreProcessor(initialTokens, diagnostics, Path.of(""));

        // Act
        List<Token> finalTokens = preProcessor.expand();

        // Assert
        // 1. Überprüfen, dass keine Fehler aufgetreten sind
        assertThat(diagnostics.hasErrors()).isFalse();

        // 2. Überprüfen, dass der .ROUTINE-Block aus dem Token-Stream entfernt wurde
        List<String> remainingTokenTexts = finalTokens.stream().map(Token::text).toList();
        assertThat(remainingTokenTexts)
                .as("Der .ROUTINE-Block sollte aus den Tokens entfernt worden sein")
                .containsExactly("SETI", "%DR0", "DATA", ":", "0", ""); // "" ist EOF

        // 3. Überprüfen, ob die Routine korrekt im Kontext registriert wurde
        PreProcessorContext context = preProcessor.getPreProcessorContext();
        Optional<RoutineDefinition> routineOpt = context.getRoutine("INCREMENT");

        assertThat(routineOpt)
                .as("Die Routine 'INCREMENT' sollte im Kontext registriert sein")
                .isPresent();

        RoutineDefinition routine = routineOpt.get();
        assertThat(routine.name().text()).isEqualTo("INCREMENT");
        assertThat(routine.parameters()).hasSize(1);
        assertThat(routine.parameters().get(0).text()).isEqualTo("REG");

        // KORREKTUR: Der Body beginnt mit 'ADDI' und hat 6 Tokens
        // Body: ADDI, REG, DATA, :, 1, NEWLINE
        assertThat(routine.body()).hasSize(6);
        assertThat(routine.body().get(0).type()).isEqualTo(TokenType.OPCODE);
        assertThat(routine.body().get(0).text()).isEqualTo("ADDI");
    }
}