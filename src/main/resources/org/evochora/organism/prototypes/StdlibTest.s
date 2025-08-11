# =================================================================
# Prototyp: StdlibTest.s
# Beschreibung: Visueller Test für Prozeduren aus stdlib.s
# =================================================================

# 1. Explizites Laden der benötigten Bibliothek
.FILE "lib/stdlib.s"

# 2. Aufbau der Test-Welt
# Platziere ein fremdes Objekt bei 11|10 (relativ zum Start bei 10|10)
.PLACE STRUCTURE:99 11|10

# 3. Register-Aliase für den Test
.REG %VEC_TARGET 2
.REG %FLAG_OUT   3

# 4. Eine Wrapper-Prozedur für den Testaufruf
.PROC TEST_PROC WITH VEC FLAG
    CALL stdlib.IS_PASSABLE WITH VEC FLAG
    RET
.ENDP

# 5. Importieren der Prozeduren für den Aufruf im Hauptprogramm
.IMPORT stdlib.IS_PASSABLE AS IS_PASSABLE_INSTANCE

# ================================================
# === Hauptprogramm
# ================================================
MAIN:
    # Setze den DP auf die aktuelle Position, damit SCAN/IFM-Befehle
    # relativ zur aktuellen Position des Organismus arbeiten.
    SYNC

    # Wir wollen die Zelle rechts von uns prüfen (absolut: 11|10)
    SETV %VEC_TARGET 1|0

    # Rufe die Prozedur auf. Das Ergebnis landet in %DR3 (%FLAG_OUT)
    CALL TEST_PROC WITH %VEC_TARGET %FLAG_OUT

    # Endlosschleife, damit der Zustand im UI beobachtet werden kann
    JMPI MAIN