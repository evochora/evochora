# ===================================================
# Routine Library: math.s (Korrigierte Version)
# ===================================================

.ROUTINE ADD_TWO_VALUES REG_A REG_B REG_RETURN_ADDR
    # KORREKTE AUFRUFKONVENTION:
    # 1. Rücksprungadresse sichern
    POP  REG_RETURN_ADDR
    # 2. Parameter holen
    POP  REG_B
    POP  REG_A
    # 3. Operation ausführen
    ADDR REG_A REG_B
    # 4. Ergebnis auf den Stack legen
    PUSH REG_A
    # 5. Mit JMPR zur gesicherten Adresse zurückkehren
    JMPR REG_RETURN_ADDR
.ENDR