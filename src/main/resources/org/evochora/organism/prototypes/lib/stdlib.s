# =================================================================
# Standard-Bibliothek: stdlib.s
# =================================================================

.PROC stdlib.IS_PASSABLE WITH VEC_TARGET FLAG_OUT
    .EXPORT stdlib.IS_PASSABLE
    .PREG %PR_CELL_CONTENT 0

    SETI FLAG_OUT DATA:0

    SCAN %PR_CELL_CONTENT VEC_TARGET
    IFI %PR_CELL_CONTENT CODE:0
    JMPI .IS_PASSABLE_EXIT

    IFMI VEC_TARGET
    JMPI .IS_PASSABLE_EXIT

    JMPI .EXIT_NO_CHANGE

.IS_PASSABLE_EXIT:
    SETI FLAG_OUT DATA:1

.EXIT_NO_CHANGE:
    RET
.ENDP