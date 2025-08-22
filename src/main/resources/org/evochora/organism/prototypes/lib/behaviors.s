# =================================================================
# Verhaltens-Bibliothek: behaviors.s
# Enthält die übergeordneten, in sich geschlossenen
# Verhaltensblöcke des Organismus.
# =================================================================

.REQUIRE "strategies.s" AS STRATEGIES
.REQUIRE "tactics.s" AS TACTICS

# --- Verhalten: Energiesuche ---
#
# Zweck:
#   Definiert den kompletten, unterbrechbaren Zyklus der Energiesuche.
#
.PROC SEEK_ENERGY_RANDOM EXPORT
    # Schritt 1: Führe die Ernte-Strategie aus.
    CALL STRATEGIES.HARVEST_SURROUNDINGS

    # Schritt 2: Mache einen einzelnen, intelligenten Schritt.
    CALL TACTICS.STEP_RANDOMLY

    # Das Verhalten ist für diesen Tick abgeschlossen.
    RET
.ENDP


# --- Verhalten: Reproduktion (Mock) ---
#
.PROC REPRODUCE EXPORT
    NOP
    RET
.ENDP