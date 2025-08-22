# ================================================
# Hauptprogramm: main.s (Effizienz-Optimiert)
# ================================================
# Zweck:
#   Dieses Programm steuert einen Organismus, dessen primäres
#   Ziel das Überleben durch effiziente Energiesuche ist.
#   Es nutzt moderne ISA-Instruktionen, um die Anzahl der
#   benötigten Ticks pro Zyklus zu minimieren.
# ================================================

# --- 1. Definitionen und logische Abhängigkeiten ---
#    - Konstanten und Register-Aliase für Lesbarkeit
#    - .REQUIRE deklariert, welche Module logisch benötigt werden.
.DEFINE ENERGY_REPRODUCTION_THRESHOLD DATA:5000
.REG %CURRENT_ENERGY %DR0
.REG %TEMP_CALC_REG %DR6
.REG %LAST_DIRECTION %DR7

.REQUIRE "lib/behaviors.s" AS BEHAVIORS

# --- 2. Hauptprogramm-Code ---
#    - .ORG legt den Startpunkt in der Welt fest.
.ORG 0|0

# Initialisierung: Die letzte Bewegungsrichtung wird auf einen Nullvektor
# gesetzt, damit der Filter im ersten Tick keine gültige Richtung blockiert.
SETV %LAST_DIRECTION 0|0

MAIN_LOOP:
    # Lade die aktuelle Energie in unser Alias-Register. (1 Tick)
    NRG %CURRENT_ENERGY

    # Entscheide basierend auf der Energie, welches Verhalten ausgeführt wird.
    # GTI (Greater Than Immediate) ist eine schnelle Ein-Tick-Prüfung.
    GTI %CURRENT_ENERGY ENERGY_REPRODUCTION_THRESHOLD
        JMPI START_REPRODUCTION

    # Wenn nicht genug Energie für die Reproduktion da ist, suche weiter.
    JMPI SEEK_ENERGY

# --- Verhaltens-Aufrufe ---
# Jeder Block ruft eine spezialisierte Prozedur aus der Verhaltens-Bibliothek
# auf und springt danach zurück in die Hauptschleife.

START_REPRODUCTION:
    # Ruft die (gemockte) Reproduktions-Routine auf.
    CALL BEHAVIORS.REPRODUCE
    JMPI MAIN_LOOP

SEEK_ENERGY:
    # Die "SEEK_ENERGY_RANDOM"-Routine erwartet als Parameter einen Vektor,
    # der die *zu vermeidende* Richtung angibt. Wir berechnen hier die
    # Umkehrung der letzten Richtung (0 - last_vec = -last_vec).
    SETV %TEMP_CALC_REG 0|0
    SUBR %TEMP_CALC_REG %LAST_DIRECTION # %TEMP_CALC_REG = -%LAST_DIRECTION

    # Rufe das Verhalten auf. Die neue Bewegungsrichtung wird direkt in
    # %LAST_DIRECTION zurückgeschrieben, um sie für den nächsten Tick zu speichern.
    CALL BEHAVIORS.SEEK_ENERGY_RANDOM WITH %TEMP_CALC_REG %LAST_DIRECTION
    JMPI MAIN_LOOP

# --- 3. Physisches Einbinden der Module ---
#    - Die .INCLUDE-Anweisungen laden den Code der Bibliotheken in die Welt.
#    - Jedes Modul wird auf einer eigenen Koordinate platziert, um
#      Überlappungen zu vermeiden.
.ORG 0|1
.INCLUDE "lib/stdlib.s"
.ORG 0|2
.INCLUDE "lib/stdlib_2d.s"
.ORG 0|3
.INCLUDE "lib/tactics.s"
.ORG 0|4
.INCLUDE "lib/strategies.s"
.ORG 0|5
.INCLUDE "lib/behaviors.s"