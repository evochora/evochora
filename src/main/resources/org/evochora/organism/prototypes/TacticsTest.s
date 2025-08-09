# =================================================================
# Prototyp: TacticsTest.s - Testsuite für die Taktik-Bibliothek
# =================================================================

# --- Globale Register-Aliase und Vektoren ---
.REG %TR_RESULT      0   # Ergebnisregister für Assertions (0=Erfolg, 1=Fehler)
.REG %VEC_RESULT_POS 1   # Vektor zur Platzierung des Testergebnisses
.REG %TR0            2   # Richtungsvektor (REG_DV)
.REG %TR1            3   # Temporärregister (ung. nicht verwendet hier)
.REG %TR2            4   # Flag/Typ (REG_TF)
.REG %TR3            5   # Erwarteter Wert für Vergleiche
.REG %TR4            6   # Temporäres Symbol-Register
.REG %TR5            7   # Temporäres Vektor-Register

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
    SETI %TR_RESULT DATA:1
    # Vergleicht das Ergebnis mit dem erwarteten Wert
    IFR RESULT_REG EXPECTED_REG
    # Wenn gleich, setze das Ergebnis auf Erfolg (DATA:0)
    SETI %TR_RESULT DATA:0

    # Markiere die Zelle links vom Start mit dem Ergebnis
    SETV %VEC_RESULT_POS -1|0
    POKE %TR_RESULT %VEC_RESULT_POS

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
# --- Test 1: SCAN_NEIGHBORS_FOR_TYPE – Flag = 1 bei Treffer (Zeile 1)
# ------------------------------------------------
$TEST_START 1 1
    # Vorbereitung: Platziere Energie rechts (1|0)
    SETI %TR4 ENERGY:500
    SETV %TR5 1|0
    POKE %TR4 %TR5

    # Testcode:
    SETV %TR0 1|0          # Start-Richtung: rechts
    SETI %TR2 ENERGY:0     # Zieltyp: Energie
    CALL R_TACTICS_SCAN    # Aufruf der Taktik
    SETI %TR3 DATA:1       # Erwartet: gefunden

    TEST_1_ASSERTION:
    $TEST_ASSERT %TR2 %TR3 2

    # 3 NOPs Abgrenzung
    NOP
    NOP
    NOP

    .INCLUDE tactics.SCAN_NEIGHBORS_FOR_TYPE AS R_TACTICS_SCAN WITH %TR0 %TR2


# ------------------------------------------------
# --- Test 2: SCAN_NEIGHBORS_FOR_TYPE – Richtung = 1|0 bei Treffer (Zeile 3)
# ------------------------------------------------
$TEST_START 2 3
    # Vorbereitung: Energie rechts bleibt vom vorherigen Test platziert.
    # Testcode:
    SETV %TR0 1|0          # Start-Richtung: rechts
    SETI %TR2 ENERGY:0     # Zieltyp: Energie
    CALL R_TACTICS_SCAN
    SETV %TR3 1|0          # Erwartete Richtung: rechts

    TEST_2_ASSERTION:
    $TEST_ASSERT %TR0 %TR3 3

    # 3 NOPs
    NOP
    NOP
    NOP

    #.INCLUDE tactics.SCAN_NEIGHBORS_FOR_TYPE AS R_TACTICS_SCAN WITH %TR0 %TR2


# ------------------------------------------------
# --- Test 3: SCAN_NEIGHBORS_FOR_TYPE – Flag = 0 wenn nichts gefunden (Zeile 5)
# ------------------------------------------------
$TEST_START 3 5
    # Vorbereitung: Keine Zielobjekte um den Start (rechts war zuvor belegt; wir setzen dort "leer")
    # Überschreibe rechts mit leerem Wert
    SETI %TR4 DATA:0
    SETV %TR5 1|0
    POKE %TR4 %TR5

    # Testcode:
    SETV %TR0 1|0          # Start-Richtung: rechts
    SETI %TR2 ENERGY:0     # Zieltyp: Energie
    CALL R_TACTICS_SCAN
    SETI %TR3 DATA:0       # Erwartet: nicht gefunden

    TEST_3_ASSERTION:
    $TEST_ASSERT %TR2 %TR3 4

    # 3 NOPs
    NOP
    NOP
    NOP

    #.INCLUDE tactics.SCAN_NEIGHBORS_FOR_TYPE AS R_TACTICS_SCAN WITH %TR0 %TR2


# ------------------------------------------------
# --- Test 4: SCAN_NEIGHBORS_FOR_TYPE – DV bleibt Start-Richtung bei Misserfolg (Zeile 7)
# ------------------------------------------------
$TEST_START 4 7
    # Vorbereitung: Umgebung leer (wie nach Test 3)
    # Testcode:
    SETV %TR0 1|0          # Start-Richtung: rechts
    SETI %TR2 ENERGY:0     # Zieltyp: Energie
    CALL R_TACTICS_SCAN
    SETV %TR3 1|0          # Erwartete DV: unverändert rechts

    TEST_4_ASSERTION:
    $TEST_ASSERT %TR0 %TR3 5

    # 3 NOPs
    NOP
    NOP
    NOP

    #.INCLUDE tactics.SCAN_NEIGHBORS_FOR_TYPE AS R_TACTICS_SCAN WITH %TR0 %TR2


# ------------------------------------------------
# --- Test 5: Ende der Testsuite (in Zeile 9)
# ------------------------------------------------
$TEST_START 5 9
    NOP
    JMPI END_OF_ALL_TACTICS_TESTS

END_OF_ALL_TACTICS_TESTS:
    NOP

#.INCLUDE stdlib.CHECK_CELL AS CHECK_CELL WITH %TR0 %TR2
#.INCLUDE stdlib.TURN_RIGHT AS TURN_RIGHT WITH %TR0 %TR2