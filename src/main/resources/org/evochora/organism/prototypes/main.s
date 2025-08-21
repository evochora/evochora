# ================================================
# Hauptprogramm: main.s (Version 2)
# Zweck: Eine einfache Zustandsmaschine, die basierend
# auf einer einzigen Energieschwelle zwischen den
# Verhaltensmodi wechselt.
# ================================================

# --- 1. Definitionen ---
# Es gibt nur noch einen Schwellenwert. Liegt die Energie darüber,
# wird die Reproduktion gestartet. Andernfalls wird Energie gesucht.
.DEFINE ENERGY_REPRODUCTION_THRESHOLD  DATA:1500

# Alias für das Register, in dem wir die Energie speichern.
.REG %CURRENT_ENERGY %DR0


# --- 2. Hauptschleife (State Machine) ---
# Das Programm beginnt hier und kehrt immer wieder zu diesem Punkt zurück,
# um den Zustand neu zu bewerten.
.ORG 0|0
MAIN_LOOP:
    # Lade die aktuelle Energie in unser Arbeitsregister.
    NRG %CURRENT_ENERGY

    # Prüfe die Bedingung: Ist unsere Energie größer als die Schwelle?
    GTI %CURRENT_ENERGY ENERGY_REPRODUCTION_THRESHOLD
        JMPI START_REPRODUCTION  # Wenn ja, springe zum Reproduktionsmodus.

    # Wenn die Bedingung nicht erfüllt ist (Energie ist zu niedrig),
    # fahre mit der Energiesuche fort.
    JMPI SEEK_ENERGY


# --- 3. Verhaltensmodi (als unterbrechbare Stubs) ---

# --- Reproduktionsmodus ---
START_REPRODUCTION:
    # PLATZHALTER: Führt einen Schritt der Reproduktion aus.
    # In einer echten Implementierung würde hier eine einzelne, kleine
    # Aktion stehen (z.B. ein Byte des eigenen Codes kopieren).
    NOP
    JMPI MAIN_LOOP # Unmittelbar danach: zurück zur Hauptschleife, um den Zustand neu zu prüfen.


# --- Energiesuchmodus ---
SEEK_ENERGY:
    # PLATZHALTER: Führt einen Schritt der Energiesuche aus.
    # Später würde hier eine Aktion wie "SCAN" oder "PEEK" stehen.
    NOP
    JMPI MAIN_LOOP # Unmittelbar danach: zurück zur Hauptschleife.