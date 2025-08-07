# =================================================================
# Hauptprogramm: Test von stdlib.TURN_RIGHT und stdlib.CHECK_CELL
# - Jede Routine wird in einer eigenen Zeile/Block getestet.
# - Bei Erfolg wird rechts neben die jeweilige Zeile DATA:0 geschrieben.
# - Hinweis: Wir drehen DV auf 'nach unten', damit die Zelle rechts neben
#   dem aktuellen Befehl garantiert frei bleibt (Code läuft vertikal).
# =================================================================

# --- Register-Aliase ---
.REG %DV     0   # Richtungsvektor für Tests / TURN_RIGHT (REG_DV)
.REG %TMP    1   # Temporär für TURN_RIGHT (REG_TMP)
.REG %TF     2   # In/Out-Flag für CHECK_CELL (REG_TF)
.REG %VEC    3   # Allgemeine Vektoren (1|0, 0|1)
.REG %SYM    4   # Datenliteral für POKE/Vergleiche

# --- Routine-Instanzen einbinden ---
.INCLUDE stdlib.TURN_RIGHT AS TR WITH %DV %TMP
.INCLUDE stdlib.CHECK_CELL AS CC WITH %DV %TF

# --- Global: Codefluss vertikal machen (DV = 0|1) ---
.ORG 0|0
SETV %VEC 0|1
TURN %VEC

# === Zeile 1: Test TURN_RIGHT ===
.ORG 0|2             # Start der Test-Zeile für TURN_RIGHT
# Vorbereitung
SETV %DV 1|0         # Erwartet: nach Rechts
CALL TR              # Dreht um 90°: 1|0 -> 0|1
# Prüfen
SETV %TMP 0|1        # Erwarteter Wert nach Drehung
IFR %DV %TMP         # Wenn gleich, springe zu OK
JMPI TR_OK
JMPI TR_END          # sonst: nichts markieren
TR_OK:
# Erfolg markieren: rechts neben die Zeile DATA:0 schreiben
SYNC                 # DP = aktuelle IP-Position (Start der Zeile)
SETV %VEC 1|0        # Nach rechts
SETI %SYM DATA:0
POKE %SYM %VEC       # Schreibt nur, wenn Zelle leer ist (hier der Fall)
TR_END:

# === Zeile 2: Test CHECK_CELL ===
.ORG 0|6             # Start der Test-Zeile für CHECK_CELL
# Vorbereitung der Testumgebung: Unterhalb eine DATA-Zelle ablegen
SYNC                 # DP = aktuelle IP-Position dieser Zeile
SETV %VEC 0|1        # Richtung: unten
SETI %SYM DATA:99    # Beliebiger DATA-Wert (nur Typ zählt)
POKE %SYM %VEC       # Lege DATA direkt unter die Zeile
# Routine aufrufen
SETV %DV 0|1         # Wir prüfen die Zelle unterhalb
SETI %TF DATA:0      # Gesuchter Typ: DATA
CALL CC              # %TF wird 1 (gefunden) oder 0 (nicht) setzen
# Prüfen auf Erfolg
SETI %SYM DATA:1
IFR %TF %SYM
JMPI CC_OK
JMPI CC_END
CC_OK:
# Erfolg markieren: rechts neben die Zeile DATA:0 schreiben
SYNC
SETV %VEC 1|0
SETI %SYM DATA:0
POKE %SYM %VEC
CC_END:
