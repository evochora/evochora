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
#   - Abhängigkeiten: stdlib.CHECK_CELL, stdlib.TURN_RIGHT
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
.ENDR
    NOP

.INCLUDE stdlib.CHECK_CELL AS CHECK_CELL WITH REG_DV REG_TF
.INCLUDE stdlib.TURN_RIGHT AS TURN_RIGHT WITH REG_DV REG_TF
