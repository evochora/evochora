# =================================================================
# Taktik-Bibliothek: tactics.s
#
# Enthält Routinen, die primitive Operationen aus stdlib.s zu
# einfachen, aber nützlichen Verhaltensmustern kombinieren.
# =================================================================

# --- Routine: tactics.SCAN_NEIGHBORS_FOR_TYPE ---
#
# Zweck:
#   Prüft die 4 Nachbarzellen im Uhrzeigersinn (ausgehend von REG_DV) auf einen Zieltyp.
#   Stoppt beim ersten Treffer. Gibt Richtung (in REG_DV) und Flag (in REG_TF) zurück.
#
# Signatur:
#   .ROUTINE SCAN_NEIGHBORS_FOR_TYPE REG_DV REG_TF
#   - Eingabe:
#       REG_DV (In/Out): Start-Richtung (Einheitsvektor).
#       REG_TF (In):     Zieltyp-Literal (z. B. ENERGY:0).
#   - Ausgabe:
#       Bei Erfolg:   REG_TF = DATA:1, REG_DV = gefundene Richtung.
#       Bei Misserfolg: REG_TF = DATA:0, REG_DV = ursprüngliche Start-Richtung (wiederhergestellt).
#
# Hinweise:
#   - Benötigt nur REG_DV und REG_TF. Weitere Zustände werden über den Stack verwaltet.
#   - Abhängigkeiten (nested .INCLUDE): stdlib.CHECK_CELL, stdlib.TURN_RIGHT.
#   - Platzbedarf / Zeilenbedarf:
#       Diese Routine belegt insgesamt 3 Zeilen relativ zu ihrer Platzierung:
#         y     : Hauptlogik der Routine
#         y + 1 : inkludierte stdlib.CHECK_CELL
#         y + 2 : inkludierte stdlib.TURN_RIGHT
#       Die .ORG Direktiven innerhalb der Routine sorgen dafür, dass jede Include auf
#       einer eigenen (relativen) Zeile landet. Der Aufrufer sollte entsprechend Platz
#       einplanen, indem nachfolgende Anweisungen um 3 Zeilen verschoben werden.
#
.ROUTINE SCAN_NEIGHBORS_FOR_TYPE REG_DV REG_TF

    # Startzustand sichern
    PUSH REG_DV      # [ ... , SAVED_DV ]
    PUSH REG_TF      # [ ... , SAVED_DV, SAVED_TARGET ]

    # --- Versuch 1 (aktueller REG_DV) ---
TRY_1:
    # Zieltyp aus Stack in REG_TF holen und wieder zurücklegen (Stackhöhe konstant halten)
    POP REG_TF
    PUSH REG_TF
    CALL CHECK_CELL
    IFI REG_TF DATA:1
    JMPI FOUND

    # --- Versuch 2 (REG_DV nach rechts gedreht) ---
    CALL TURN_RIGHT
TRY_2:
    POP REG_TF
    PUSH REG_TF
    CALL CHECK_CELL
    IFI REG_TF DATA:1
    JMPI FOUND

    # --- Versuch 3 ---
    CALL TURN_RIGHT
TRY_3:
    POP REG_TF
    PUSH REG_TF
    CALL CHECK_CELL
    IFI REG_TF DATA:1
    JMPI FOUND

    # --- Versuch 4 ---
    CALL TURN_RIGHT
TRY_4:
    POP REG_TF
    PUSH REG_TF
    CALL CHECK_CELL
    IFI REG_TF DATA:1
    JMPI FOUND

    # --- Kein Treffer in 4 Richtungen ---
NOT_FOUND:
    POP REG_TF      # SAVED_TARGET verwerfen
    POP REG_DV      # ursprüngliche Richtung wiederherstellen
    SETI REG_TF DATA:0
    RET

FOUND:
    # Stack bereinigen, ohne REG_DV zu ändern
    POP REG_TF      # SAVED_TARGET verwerfen
    POP REG_TF      # SAVED_DV verwerfen (REG_DV bleibt die Treffer-Richtung)
    SETI REG_TF DATA:1
    RET

    # Jede Include auf einer eigenen relativen Zeile platzieren:
    .ORG 0|1
    .INCLUDE stdlib.CHECK_CELL AS CHECK_CELL WITH REG_DV REG_TF
    .ORG 0|2
    .INCLUDE stdlib.TURN_RIGHT AS TURN_RIGHT WITH REG_DV REG_TF
.ENDR
