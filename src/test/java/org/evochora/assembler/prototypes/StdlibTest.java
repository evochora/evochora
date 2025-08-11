package org.evochora.assembler.prototypes;

import org.evochora.organism.Organism;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class StdlibTest extends TestBase {

    // Diese Methode erstellt den Rahmen für unsere Tests.
    // Sie importiert die stdlib und ruft die zu testende Prozedur auf.
    private String getTestCode(String setupCode) {
        return setupCode + """

            .FILE "lib/stdlib.s"

            .REG %VEC_TARGET 2
            .REG %FLAG_OUT   3

            .PROC TEST_PROC WITH VEC FLAG
                CALL stdlib.IS_PASSABLE WITH VEC FLAG
                RET
            .ENDP

            .IMPORT stdlib.IS_PASSABLE AS IS_PASSABLE_INSTANCE

            # Haupt-Testausführung
            SETV %VEC_TARGET 1|0
            CALL TEST_PROC WITH %VEC_TARGET %FLAG_OUT
            """;
    }

    @Test
    void isPassable_shouldReturnTrue_forEmptyCell() {
        String testCode = getTestCode(""); // Kein Setup, die Zelle bei (1,0) ist leer.
        Organism org = runTest(testCode, 2);
        assertThat(org.getDr(3)).isEqualTo(toData(1)); // Erwartet: DATA:1 (passierbar)
    }

    @Test
    void isPassable_shouldReturnFalse_forForeignCell() {
        // Vorbereitung: Platziere eine fremde Zelle mit .PLACE
        String setup = ".PLACE STRUCTURE:99 1|0";
        String testCode = getTestCode(setup);
        Organism org = runTest(testCode, 2);
        assertThat(org.getDr(3)).isEqualTo(toData(0)); // Erwartet: DATA:0 (blockiert)
    }

    @Test
    void isPassable_shouldReturnTrue_forOwnedCell() {
        // Vorbereitung: Der Organismus schreibt selbst in die Zelle und wird zum Besitzer.
        String setup = """
            .REG %TEMP_VAL 5
            .REG %VEC_POKE 6
            SETV %VEC_POKE 1|0
            SETI %TEMP_VAL DATA:42
            POKE %TEMP_VAL %VEC_POKE
            """;
        String testCode = getTestCode(setup);
        Organism org = runTest(testCode, 3); // Ein Tick mehr für die POKE-Instruktion
        assertThat(org.getDr(3)).isEqualTo(toData(1)); // Erwartet: DATA:1 (passierbar)
    }
}