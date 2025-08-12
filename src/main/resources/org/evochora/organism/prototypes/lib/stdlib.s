# =================================================================
# Standard-Bibliothek: stdlib.s
# =================================================================

.PROC stdlib.IS_PASSABLE WITH VEC_TARGET FLAG_OUT
    .EXPORT stdlib.IS_PASSABLE
    .PREG %PR_CELL_CONTENT 0

    # Standardmäßig auf "nicht passierbar" setzen
    SETI FLAG_OUT DATA:0
    SCAN %PR_CELL_CONTENT VEC_TARGET

    # Wenn die Zelle leer ist, ist sie passierbar
    IFI %PR_CELL_CONTENT CODE:0
    JMPI IS_PASSABLE_EXIT

    # Wenn die Zelle Teil des eigenen Körpers ist, ist sie passierbar
    IFMR VEC_TARGET
    JMPI IS_PASSABLE_EXIT

    # Ansonsten: nicht passierbar. Mit FLAG_OUT=0 zurückkehren.
    RET

IS_PASSABLE_EXIT:
    SETI FLAG_OUT DATA:1
    RET
.ENDP