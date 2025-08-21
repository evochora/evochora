# =================================================================
# Strategie-Bibliothek: strategies.s
# Enthält konkrete Algorithmen, die Taktiken zu einem Plan
# kombinieren.
# =================================================================

# Wir deklarieren, dass diese Datei Funktionen aus "tactics.s" benötigt.
# Der Compiler weiß dadurch, dass er Aufrufe wie "TACTICS.HARVEST_CELL"
# verstehen muss.
.REQUIRE "lib/tactics.s" AS TACTICS

# --- Strategie: HARVEST_SURROUNDINGS ---
#
# Zweck:
#   Führt die Taktik "HARVEST_CELL" für alle 4 Nachbarzellen aus.
#   Dies ist der strategische Plan, um die unmittelbare Umgebung
#   nach Nahrung abzusuchen.
#
.PROC HARVEST_SURROUNDINGS EXPORT
    # Wir leihen uns zwei Prozedur-Register für unsere Arbeit.
    # Sie werden am Ende der Prozedur automatisch wieder freigegeben.
    .PREG %DIRECTION 0 # %PR0 für den Richtungsvektor
    .PREG %RESULT    1 # %PR1 für das Ergebnis von HARVEST_CELL

    # 1. Prüfe Rechts (1|0)
    SETV %DIRECTION 1|0
    CALL TACTICS.HARVEST_CELL WITH %DIRECTION %RESULT

    # 2. Prüfe Links (-1|0)
    SETV %DIRECTION -1|0
    CALL TACTICS.HARVEST_CELL WITH %DIRECTION %RESULT

    # 3. Prüfe Unten (0|1)
    SETV %DIRECTION 0|1
    CALL TACTICS.HARVEST_CELL WITH %DIRECTION %RESULT

    # 4. Prüfe Oben (0|-1)
    SETV %DIRECTION 0|-1
    CALL TACTICS.HARVEST_CELL WITH %DIRECTION %RESULT

    # Die Strategie ist für diesen Tick abgeschlossen.
    RET
.ENDP