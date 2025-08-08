# =================================================================
# Hauptprogramm: Test von stdlib.TURN_RIGHT und stdlib.CHECK_CELL
# - Jede Routine wird in einer eigenen Zeile getestet (Code läuft nach rechts).
# - Bei Erfolg wird rechts neben die jeweiligen Befehle DATA:0 geschrieben.
# =================================================================

# --- Register-Aliase ---
.REG %DV     0   # Richtungsvektor für Tests / TURN_RIGHT (REG_DV)
.REG %TMP    1   # Temporär für TURN_RIGHT (REG_TMP)
.REG %TF     2   # In/Out-Flag für CHECK_CELL (REG_TF)
.REG %VEC    3   # Allgemeine Vektoren (1|0, 0|1, etc.)
.REG %SYM    4   # Datenliteral für POKE/Vergleiche

# Springe zum ersten Test
JMPI TEST1

# === TEST 1: TURN_RIGHT ===
.PLACE STRUCTURE:1 0|2
.ORG 2|2
TEST1:
SYNC
SETV %DV 1|0       # Vorbereitung: Richtung Rechts
CALL TR            # Dreht um 90°
SETV %TMP 0|1      # Erwarteter Wert: Unten
IFR %DV %TMP
JMPI TR_OK
JMPI TR_END
TR_OK:
SETI %SYM DATA:0
SETV %VEC -1|0
POKE %SYM %VEC     # rechts neben Erfolg
TR_END:
JMPI TEST2

# --- zu testende Routine ---
.ORG 2|3
.INCLUDE stdlib.TURN_RIGHT AS TR WITH %DV %TMP


# === TEST 2: CHECK_CELL ===
.PLACE STRUCTURE:2 0|5
.ORG 2|5
TEST2:
SYNC
SETI %SYM DATA:123
SETV %VEC -1|0
PEEK %TMP %VEC     # Zielzelle leeren
POKE %SYM %VEC     # Lege DATA rechts neben Code
SETV %DV -1|0      # Richtung Rechts prüfen
SETI %TF DATA:0    # Gesuchter Typ: DATA
CALL CC            # %TF = 1 bei Erfolg
SETI %TMP DATA:1
IFR %TF %TMP
JMPI CC_OK
JMPI CC_END
CC_OK:
SETV %VEC -1|0
PEEK %TMP %VEC
SETI %SYM DATA:0
POKE %SYM %VEC     # Noch eine Zelle weiter rechts markieren
CC_END:
JMPI END

# --- zu testende Routine ---
.ORG 2|6
.INCLUDE stdlib.CHECK_CELL AS CC WITH %DV %TF

# --- Programmende ---
END:
NOP