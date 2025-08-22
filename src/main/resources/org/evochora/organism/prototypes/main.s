# ================================================
# Hauptprogramm: main.s
# ================================================

# --- 1. Definitionen und logische Abhängigkeiten ---
# Diese benötigen keinen physischen Platz in der Welt.
.DEFINE ENERGY_REPRODUCTION_THRESHOLD  DATA:5000
.REG %CURRENT_ENERGY %DR0
.REG %TEMP_CALC_REG %DR6
.REG %LAST_DIRECTION %DR7

.REQUIRE "lib/behaviors.s" AS BEHAVIORS

# --- 2. Hauptprogramm-Code ---
# Wir legen den Startpunkt unseres Programms auf 0|0 fest.
# Der Code der Module wird vom Compiler direkt im Anschluss platziert.
.ORG 0|0

# Initialisierung der letzten Richtung, damit der Filter im ersten Tick funktioniert.
SETV %LAST_DIRECTION 0|0

MAIN_LOOP:
    # Lade die aktuelle Energie.
    NRG %CURRENT_ENERGY

    # Entscheide basierend auf der Energie, welches Verhalten ausgeführt wird.
    GTI %CURRENT_ENERGY ENERGY_REPRODUCTION_THRESHOLD
        JMPI START_REPRODUCTION

    JMPI SEEK_ENERGY

# --- Verhaltens-Aufrufe ---
START_REPRODUCTION:
    CALL BEHAVIORS.REPRODUCE
    JMPI MAIN_LOOP

SEEK_ENERGY:
    # Berechne die invertierte letzte Richtung für die "Nicht zurück"-Logik.
    SETV %TEMP_CALC_REG 0|0
    SUBR %TEMP_CALC_REG %LAST_DIRECTION

    # Rufe das Verhalten auf und aktualisiere die letzte Bewegungsrichtung.
    CALL BEHAVIORS.SEEK_ENERGY_RANDOM WITH %TEMP_CALC_REG %LAST_DIRECTION
    JMPI MAIN_LOOP

# --- 3. Physisches Einbinden der Module ---
# Ohne .ORG-Anweisungen dazwischen platziert der Compiler den Code
# jedes Moduls direkt hinter den Code des vorherigen.
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