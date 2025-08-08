# =================================================================
# Prototyp: TacticsTest.s - Testsuite für die Taktik-Bibliothek
# =================================================================

# --- Globale Register-Aliase ---
.REG %REG_TEST_RESULT      0   # Globales Ergebnis-Register für die Makros (0=Erfolg, 1=Fehler)
.REG %VEC_RESULT_POS       1   # Vektor zur Platzierung des Testergebnisses

# --- Generische Register für die einzelnen Testfälle ---
.REG %REG_PARAM_1          2   # Parameter 1 für eine Routine
.REG %REG_PARAM_2          3   # Parameter 2 für eine Routine
.REG %REG_PARAM_3          4   # Parameter 3 für eine Routine
.REG %REG_PARAM_4          5   # Parameter 4 für eine Routine
.REG %REG_PARAM_5          6   # Parameter 5 für eine Routine
.REG %REG_EXPECTED         7   # Register für den erwarteten Wert

# --- Makro zur Test-Vorbereitung ---
.MACRO $TEST_START TEST_NUMBER TEST_LINE
    .PLACE STRUCTURE:TEST_NUMBER 0|TEST_LINE
    .ORG 2|TEST_LINE
    TEST_TEST_NUMBER:
        SYNC
.ENDM

# --- Makro für die Test-Überprüfung ---
.MACRO $TEST_ASSERT RESULT_REG EXPECTED_REG NEXT_TEST_LABEL
    SETI %REG_TEST_RESULT DATA:1
    IFR RESULT_REG EXPECTED_REG
    SETI %REG_TEST_RESULT DATA:0
    SETV %VEC_RESULT_POS -1|0
    POKE %REG_TEST_RESULT %VEC_RESULT_POS
    JMPI NEXT_TEST_LABEL
.ENDM

# ================================================
# === Hauptprogramm: Testkette
# ================================================
.ORG 0|0
MAIN:
    JMPI TEST_1

# ------------------------------------------------
# --- Test 1: tactics.SCAN_SURROUNDINGS (Erfolgsfall)
# ------------------------------------------------
$TEST_START 1 1
    # Vorbereitung: Platziere Energie rechts vom Start (bei 3|1)
    .PLACE ENERGY:500 1|0

    # Testcode:
    SETI %REG_PARAM_1 ENERGY:0      # Wir suchen nach Energie
    CALL R_SCANNER                 # Rufe die Scan-Routine auf

    # Überprüfung 1: War die Suche erfolgreich? (Ergebnis in %REG_PARAM_2)
    SETI %REG_EXPECTED DATA:1
    IFR %REG_PARAM_2 %REG_EXPECTED
    JMPI TEST_1_CHECK_DIRECTION

    # Wenn die erste Prüfung fehlschlägt, Test als fehlgeschlagen markieren und weiter
    SETI %REG_TEST_RESULT DATA:1
    SETV %VEC_RESULT_POS -1|0
    POKE %REG_TEST_RESULT %VEC_RESULT_POS
    JMPI TEST_2

TEST_1_CHECK_DIRECTION:
    # Überprüfung 2: Wurde die korrekte Richtung (1|0) gefunden? (Ergebnis in %REG_PARAM_3)
    SETV %REG_EXPECTED 1|0
    $TEST_ASSERT %REG_PARAM_3 %REG_EXPECTED TEST_2

# ------------------------------------------------
# --- Test 2: tactics.SCAN_SURROUNDINGS (Fehlerfall)
# ------------------------------------------------
$TEST_START 2 3
    # Vorbereitung: Stelle sicher, dass keine Energie in der Nähe ist.
    # (Wir räumen die Energie aus Test 1 vorsichtshalber weg)
    SETV %REG_PARAM_4 1|0
    PEEK %REG_PARAM_5 %REG_PARAM_4

    # Testcode:
    SETI %REG_PARAM_1 ENERGY:0
    CALL R_SCANNER

    # Überprüfung: Das Erfolgs-Flag (%REG_PARAM_2) sollte 0 sein.
    SETI %REG_EXPECTED DATA:0
    $TEST_ASSERT %REG_PARAM_2 %REG_EXPECTED TEST_3

# ------------------------------------------------
# --- Test 3: Ende der Testsuite
# ------------------------------------------------
$TEST_START 3 5
    NOP
    JMPI END_OF_ALL_TESTS


# ================================================
# === Routine-Instanziierung
# ================================================
.ORG 30|0 # Sicher abseits platziert
R_SCANNER:
    # KORREKTUR: Die Routine wird jetzt mit 'AS' instanziiert.
    .INCLUDE tactics.SCAN_SURROUNDINGS AS MY_SCANNER WITH %REG_PARAM_1 %REG_PARAM_2 %REG_PARAM_3 %REG_PARAM_4 %REG_PARAM_5

END_OF_ALL_TESTS:
    NOP