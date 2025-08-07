# ================================================
# Prototyp: RoutineTester.s (Finale Version)
# ================================================

# --- Register-Aliase ---
.REG %DR_A 0
.REG %DR_B 1
.REG %DR_RETURN 2 # WICHTIG: Das dritte Register für die Rücksprungadresse

# ================================================
# === Subroutinen für dieses Programm instanziieren
# ================================================
.ORG 20|0
# KORREKTUR: Wir übergeben jetzt alle drei benötigten Register an die Routine.
.INCLUDE MATH.ADD_TWO_VALUES AS MY_PERSONAL_ADDER WITH %DR_A %DR_B %DR_RETURN


# ================================================
# === Hauptprogramm
# ================================================
.ORG 0|0
MAIN:
    # 1. Werte vorbereiten
    SETI %DR_A DATA:100
    SETI %DR_B DATA:50

    # 2. Parameter auf den Stack legen
    PUSH %DR_A
    PUSH %DR_B

    # 3. Routine aufrufen
    CALL MY_PERSONAL_ADDER

    # 4. Ergebnis vom Stack holen
    POP %DR_A

HALT:
    JMPI HALT