# =================================================================
# Prototyp: StdlibTest.s - Testsuite für Standard-Bibliotheken
#
# Dieses Programm testet Routinen aus stdlib.s mit einem
# standardisierten Makro-Schema, das die Teststruktur automatisiert.
# =================================================================

# --- Globale Register-Aliase und Vektoren ---
.REG %DR_RESULT      0   # Ergebnis-Register für die Assertionen (0=Erfolg, 1=Fehler)
.REG %VEC_RESULT_POS 1   # Vektor zur Platzierung des Testergebnisses
.REG %DR0            2   # Richtungsvektor
.REG %DR1            3   # Temporäres Register
.REG %DR2            4   # True/False-Flag für Bedingungen
.REG %DR3            5   # Erwarteter Wert für Vergleiche
.REG %DR4            6   # Temporäres Symbol-Register
.REG %DR5            7   # Temporäres Vektor-Register

# --- Makro zur Test-Vorbereitung ---
.MACRO $TEST_START TEST_NUMBER TEST_LINE
    .PLACE STRUCTURE:TEST_NUMBER 0|TEST_LINE
    .ORG 2|TEST_LINE
    TEST_TEST_NUMBER:
        SYNC
.ENDM

# --- Makro für die Test-Überprüfung ---
.MACRO $TEST_ASSERT RESULT_REG EXPECTED_REG NEXT_TEST_NUMBER
    # Setzt Annahme: Test schlägt fehl (DATA:1)
    SETI %DR_RESULT DATA:1
    # Vergleicht das Ergebnis mit dem erwarteten Wert
    IFR RESULT_REG EXPECTED_REG
    # Wenn gleich, setze das Ergebnis auf Erfolg (DATA:0)
    SETI %DR_RESULT DATA:0

    # Markiere die Zelle links vom Start mit dem Ergebnis
    SETV %VEC_RESULT_POS -1|0
    POKE %DR_RESULT %VEC_RESULT_POS

    # Springe zum nächsten Test
    JMPI TEST_NEXT_TEST_NUMBER
.ENDM

# ================================================
# === Hauptprogramm: Testkette
# ================================================
.ORG 0|0
MAIN:
    SETV %VEC_RESULT_POS 1|0
    JMPI TEST_1 # Springt zum ersten Test

# ------------------------------------------------
# --- Test 1: TURN_RIGHT (in Zeile 1)
# ------------------------------------------------
$TEST_START 1 1
    # Testcode:
    SETV %DR0 1|0          # Startvektor ist Rechts (1|0)
    CALL R_TEST_1          # Ruft die Routine auf
    SETV %DR3 0|1          # Erwartetes Ergebnis ist Unten (0|1)

    TEST_1_ASSERTION:
    # Überprüfung des Ergebnisses und Sprung zum nächsten Test
    $TEST_ASSERT %DR0 %DR3 2

    # 3 NOPs zur Abgrenzung zwischen Testcode und Routine
    NOP
    NOP
    NOP

    .INCLUDE stdlib.TURN_RIGHT AS R_TEST_1 WITH %DR0 %DR1



# ------------------------------------------------
# --- Test 2: CHECK_CELL (in Zeile 3)
# ------------------------------------------------
$TEST_START 2 3
    # Testcode:
    SETI %DR4 DATA:123
    SETV %DR5 -1|0
    POKE %DR4 %DR5
    SETV %DR0 -1|0
    SETI %DR2 DATA:0
    CALL R_TEST_2
    PEEK %DR4 %DR5
    SETI %DR3 DATA:1

    TEST_2_ASSERTION:
    # Überprüfung des Ergebnisses und Sprung zum nächsten Test
    $TEST_ASSERT %DR2 %DR3 3

    # 3 NOPs zur Abgrenzung zwischen Testcode und Routine
    NOP
    NOP
    NOP

    .INCLUDE stdlib.CHECK_CELL AS R_TEST_2 WITH %DR0 %DR2



# ------------------------------------------------
# --- Test 3: TURN_LEFT (in Zeile 5)
# ------------------------------------------------
$TEST_START 3 5
    # Testcode:
    SETV %DR0 1|0          # Startvektor ist Rechts (1|0)
    CALL R_TEST_3          # Ruft die Routine auf
    SETV %DR3 0|-1         # Erwartetes Ergebnis ist Oben (0|-1)

    TEST_3_ASSERTION:
    # Überprüfung des Ergebnisses und Sprung zum nächsten Test
    $TEST_ASSERT %DR0 %DR3 4

    # 3 NOPs zur Abgrenzung zwischen Testcode und Routine
    NOP
    NOP
    NOP

    .INCLUDE stdlib.TURN_LEFT AS R_TEST_3 WITH %DR0 %DR1



# ------------------------------------------------
# --- Test 4: CHECK_EMPTY (in Zeile 7)
# ------------------------------------------------
$TEST_START 4 7
    # Testcode:
    SETV %DR0 1|0          # Prüfe die Zelle rechts vom Start
    CALL R_TEST_4          # Ruft CHECK_EMPTY auf
    SETI %DR3 DATA:1       # Erwartetes Ergebnis: leer → DATA:1

    TEST_4_ASSERTION:
    # Überprüfung des Ergebnisses und Sprung zum nächsten Test
    $TEST_ASSERT %DR2 %DR3 5

    # 3 NOPs zur Abgrenzung zwischen Testcode und Routine
    NOP
    NOP
    NOP

    .INCLUDE stdlib.CHECK_EMPTY AS R_TEST_4 WITH %DR0 %DR2



# ------------------------------------------------
# --- Test 5: Ende der Testsuite (in Zeile 9)
# ------------------------------------------------
$TEST_START 5 9
    NOP
    JMPI END_OF_ALL_TESTS

END_OF_ALL_TESTS:
    NOP