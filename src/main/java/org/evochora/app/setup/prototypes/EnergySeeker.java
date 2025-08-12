// src/main/java/org/evochora/organism/prototypes/EnergySeeker.java
package org.evochora.app.setup.prototypes;

import org.evochora.compiler.internal.legacy.AssemblyProgram;

public class EnergySeeker extends AssemblyProgram {

    @Override
    public String getProgramCode() {
        return """
                # ======================================================================
                # EnergySeeker v9.3 - Final, Robust Prototype
                # - Spatially compact layout with NOP corridors
                # - Chaotic search pattern based on energy level
                # - Rich and accessible energy environment for extensive testing
                # ======================================================================

                # --- Test Environment (80+ energy packets, safe area) ---
                .PLACE ENERGY:100 10|1
                .PLACE ENERGY:100 10|-1
                .PLACE ENERGY:150 10|3
                .PLACE ENERGY:200 10|5
                .PLACE ENERGY:250 10|9
                .PLACE ENERGY:250 10|11
                .PLACE ENERGY:250 10|13
                .PLACE ENERGY:250 10|15
                .PLACE ENERGY:250 10|17
                .PLACE ENERGY:250 10|19
                .PLACE ENERGY:100 11|1
                .PLACE ENERGY:100 11|-1
                .PLACE ENERGY:150 11|3
                .PLACE ENERGY:200 11|5
                .PLACE ENERGY:250 11|9
                .PLACE ENERGY:250 11|11
                .PLACE ENERGY:250 11|13
                .PLACE ENERGY:250 11|15
                .PLACE ENERGY:250 11|17
                .PLACE ENERGY:250 11|19
                .PLACE ENERGY:100 12|1
                .PLACE ENERGY:100 12|-1
                .PLACE ENERGY:150 12|3
                .PLACE ENERGY:200 12|5
                .PLACE ENERGY:250 12|9
                .PLACE ENERGY:250 12|11
                .PLACE ENERGY:250 12|13
                .PLACE ENERGY:250 12|15
                .PLACE ENERGY:250 12|17
                .PLACE ENERGY:250 12|19
                .PLACE ENERGY:100 13|1
                .PLACE ENERGY:150 13|3
                .PLACE ENERGY:200 13|5
                .PLACE ENERGY:250 13|9
                .PLACE ENERGY:250 13|11
                .PLACE ENERGY:250 13|13
                .PLACE ENERGY:250 13|15
                .PLACE ENERGY:250 13|17
                .PLACE ENERGY:250 13|19
                .PLACE ENERGY:100 16|1
                .PLACE ENERGY:150 16|3
                .PLACE ENERGY:200 16|5
                .PLACE ENERGY:250 16|9
                .PLACE ENERGY:250 16|11
                .PLACE ENERGY:250 16|13
                .PLACE ENERGY:250 16|15
                .PLACE ENERGY:250 16|17
                .PLACE ENERGY:250 16|19
                .PLACE ENERGY:100 19|1
                .PLACE ENERGY:150 19|3
                .PLACE ENERGY:200 19|5
                .PLACE ENERGY:250 19|9
                .PLACE ENERGY:250 19|11
                .PLACE ENERGY:250 19|13
                .PLACE ENERGY:250 19|15
                .PLACE ENERGY:250 19|17
                .PLACE ENERGY:250 19|19
                .PLACE ENERGY:100 22|1
                .PLACE ENERGY:150 22|3
                .PLACE ENERGY:200 22|5
                .PLACE ENERGY:250 22|9
                .PLACE ENERGY:250 22|11
                .PLACE ENERGY:250 22|13
                .PLACE ENERGY:250 22|15
                .PLACE ENERGY:250 22|17
                .PLACE ENERGY:250 22|19
                .PLACE ENERGY:100 25|1
                .PLACE ENERGY:150 25|3
                .PLACE ENERGY:200 25|5
                .PLACE ENERGY:0 25|7
                .PLACE ENERGY:250 25|9
                .PLACE ENERGY:250 25|11
                .PLACE ENERGY:250 25|13
                .PLACE ENERGY:250 25|15
                .PLACE ENERGY:250 25|17
                .PLACE ENERGY:250 25|19
                .PLACE ENERGY:100 28|1
                .PLACE ENERGY:150 28|3
                .PLACE ENERGY:200 28|5
                .PLACE ENERGY:100 28|7
                .PLACE ENERGY:250 28|9
                .PLACE ENERGY:250 28|11
                .PLACE ENERGY:250 28|13
                .PLACE ENERGY:250 28|15
                .PLACE ENERGY:250 28|17
                .PLACE ENERGY:250 28|19
                .PLACE ENERGY:100 31|1
                .PLACE ENERGY:150 31|3
                .PLACE ENERGY:200 31|5
                .PLACE ENERGY:100 31|7
                .PLACE ENERGY:250 31|9
                .PLACE ENERGY:250 31|11
                .PLACE ENERGY:250 31|13
                .PLACE ENERGY:250 31|15
                .PLACE ENERGY:250 31|17
                .PLACE ENERGY:250 31|19
                .PLACE ENERGY:100 34|1
                .PLACE ENERGY:150 34|3
                .PLACE ENERGY:200 34|5
                .PLACE ENERGY:100 34|7
                .PLACE ENERGY:250 34|9
                .PLACE ENERGY:250 34|11
                .PLACE ENERGY:250 34|13
                .PLACE ENERGY:250 34|15
                .PLACE ENERGY:250 34|17
                .PLACE ENERGY:250 34|19
                .PLACE ENERGY:100 37|1
                .PLACE ENERGY:150 37|3
                .PLACE ENERGY:200 37|5
                .PLACE ENERGY:100 37|7
                .PLACE ENERGY:250 37|9
                .PLACE ENERGY:250 37|11
                .PLACE ENERGY:250 37|13
                .PLACE ENERGY:250 37|15
                .PLACE ENERGY:250 37|17
                .PLACE ENERGY:250 37|19
                .PLACE ENERGY:100 40|1
                .PLACE ENERGY:150 40|3
                .PLACE ENERGY:200 40|5
                .PLACE ENERGY:100 40|7
                .PLACE ENERGY:250 40|9
                .PLACE ENERGY:250 40|11
                .PLACE ENERGY:250 40|13
                .PLACE ENERGY:250 40|15
                .PLACE ENERGY:250 40|17
                .PLACE ENERGY:250 40|19
                .PLACE ENERGY:100 43|1
                .PLACE ENERGY:150 43|3
                .PLACE ENERGY:200 43|5
                .PLACE ENERGY:100 43|7
                .PLACE ENERGY:250 43|9
                .PLACE ENERGY:250 43|11
                .PLACE ENERGY:250 43|13
                .PLACE ENERGY:250 43|15
                .PLACE ENERGY:250 43|17
                .PLACE ENERGY:250 43|19
                .PLACE ENERGY:100 46|1
                .PLACE ENERGY:150 46|3
                .PLACE ENERGY:200 46|5
                .PLACE ENERGY:100 46|7
                .PLACE ENERGY:250 46|9
                .PLACE ENERGY:250 46|11
                .PLACE ENERGY:250 46|13
                .PLACE ENERGY:250 46|15
                .PLACE ENERGY:250 46|17
                .PLACE ENERGY:250 46|19
                .PLACE ENERGY:100 49|1
                .PLACE ENERGY:150 49|3
                .PLACE ENERGY:200 49|5
                .PLACE ENERGY:100 49|7
                .PLACE ENERGY:250 49|9
                .PLACE ENERGY:250 49|11
                .PLACE ENERGY:250 49|13
                .PLACE ENERGY:250 49|15
                .PLACE ENERGY:250 49|17
                .PLACE ENERGY:250 49|19
                .PLACE ENERGY:100 52|1
                .PLACE ENERGY:150 52|3
                .PLACE ENERGY:200 52|5
                .PLACE ENERGY:100 52|7
                .PLACE ENERGY:250 52|9
                .PLACE ENERGY:100 55|1
                .PLACE ENERGY:150 55|3
                .PLACE ENERGY:200 55|5
                .PLACE ENERGY:100 55|7
                .PLACE ENERGY:250 55|9
                .PLACE ENERGY:100 58|1
                .PLACE ENERGY:150 58|3
                .PLACE ENERGY:200 58|5
                .PLACE ENERGY:100 58|7
                .PLACE ENERGY:250 58|9
                .PLACE ENERGY:100 61|1
                .PLACE ENERGY:150 61|3
                .PLACE ENERGY:200 61|5
                .PLACE ENERGY:100 61|7
                .PLACE ENERGY:250 61|9
                .PLACE ENERGY:100 64|1
                .PLACE ENERGY:150 64|3
                .PLACE ENERGY:200 64|5
                .PLACE ENERGY:100 64|7
                .PLACE ENERGY:250 64|9
                .PLACE ENERGY:100 67|1
                .PLACE ENERGY:150 67|3
                .PLACE ENERGY:200 67|5
                .PLACE ENERGY:100 67|7
                .PLACE ENERGY:250 67|9
                .PLACE ENERGY:100 70|1
                .PLACE ENERGY:150 70|3
                .PLACE ENERGY:200 70|5
                .PLACE ENERGY:100 70|7
                .PLACE ENERGY:250 70|9
                
                .PLACE STRUCTURE:1 45|8
                .PLACE STRUCTURE:1 46|8
                .PLACE STRUCTURE:1 47|8

                # --- Register Aliases ---
                .REG %REG_TEMP 0
                .REG %VEC_DV 1
                .REG %VEC_RIGHT 2
                .REG %REG_NRG 3
                .REG %REG_THRESHOLD 4
                .REG %REG_RETURN 5
                .REG %REG_COUNTER 6

                # ======================================================================
                # === 1: MAIN PROGRAM (ROW 0) ===
                # ======================================================================
                .PLACE STRUCTURE:1 -1|0
                .ORG 0|0
                MAIN_PROGRAM:
                    SETI %REG_THRESHOLD DATA:2000
                    SETI %REG_COUNTER DATA:15
                    SETV %VEC_DV 1|0
                    SYNC
                    NOP

                MAIN_LOOP:
                    NRG %REG_NRG
                    GTR %REG_NRG %REG_THRESHOLD
                    JMPI READY_TO_REPLICATE
                    NOP 

                    SETV %REG_RETURN AFTER_SEARCH
                    PUSH %REG_RETURN
                    JMPI SEEK_ENERGY_SUBROUTINE

                AFTER_SEARCH:
                    JMPI MAIN_LOOP

                READY_TO_REPLICATE:
                    NOP
                    JMPI READY_TO_REPLICATE
                
                # --- Vertical NOP Corridor ---
                .ORG 20|1
                NOP

                # ======================================================================
                # === 2: SEEK_ENERGY SUBROUTINE (ROW 2) ===
                # ======================================================================
                .PLACE STRUCTURE:2 -1|2
                .ORG 0|2
                SEEK_ENERGY_SUBROUTINE:
                    POP %REG_RETURN
                    PUSH %REG_TEMP
                    PUSH %VEC_RIGHT
                    PUSH %REG_COUNTER
                    NOP

                    SUBI %REG_COUNTER DATA:1
                    NOP
                    
                    GTI %REG_COUNTER DATA:0
                    JMPI MOVE_LOGIC
                    NOP

                    JMPI TURN_LOGIC
                
                # --- Vertical NOP Corridor ---
                .ORG 20|3
                NOP

                # ======================================================================
                # === 3: MOVE_LOGIC SUBROUTINE (ROW 4) ===
                # ======================================================================
                .PLACE STRUCTURE:3 -1|4
                .ORG 0|4
                MOVE_LOGIC:
                    SCAN %REG_TEMP %VEC_DV
                    NOP
                    
                    IFTI %REG_TEMP ENERGY:0
                    JMPI CHECK_ENERGY_VALUE
                    JMPI CHECK_IF_EMPTY

                CHECK_ENERGY_VALUE:
                    GTI %REG_TEMP ENERGY:0
                    JMPI COLLECT_ENERGY
                    JMPI TURN_LOGIC

                CHECK_IF_EMPTY:
                    IFI %REG_TEMP CODE:0
                    JMPI MOVE_FORWARD
                    
                    JMPI TURN_LOGIC
                
                # --- Vertical NOP Corridor ---
                .ORG 30|5
                NOP

                # ======================================================================
                # === 4: ACTION SUBROUTINES (ROWS 6-10) ===
                # ======================================================================
                .PLACE STRUCTURE:4 -1|6
                .ORG 0|6
                MOVE_FORWARD:
                    SEEK %VEC_DV
                    JMPI END_SUBROUTINE

                .PLACE STRUCTURE:5 -1|8
                .ORG 0|8
                COLLECT_ENERGY:
                    PEEK %REG_TEMP %VEC_DV
                    JMPI END_SUBROUTINE
                
                .PLACE STRUCTURE:6 -1|10
                .ORG 0|10
                TURN_LOGIC:
                    NRG %REG_COUNTER
                    NADI %REG_COUNTER DATA:15
                    ADDI %REG_COUNTER DATA:5
                    NOP
                    
                    $CALCULATE_RIGHT %VEC_DV %VEC_RIGHT
                    SETR %VEC_DV %VEC_RIGHT
                    JMPI END_SUBROUTINE
                
                # --- Vertical NOP Corridor ---
                .ORG 30|11
                NOP
                
                # ======================================================================
                # === 9: UTILITY SUBROUTINE: END (ROW 12) ===
                # ======================================================================
                .PLACE STRUCTURE:99 -1|12
                .ORG 0|12
                END_SUBROUTINE:
                    POP %REG_COUNTER
                    POP %VEC_RIGHT
                    POP %REG_TEMP
                    NOP
                    JMPR %REG_RETURN

                # --- MACRO: CALCULATE_RIGHT (No physical location) ---
                .MACRO $CALCULATE_RIGHT CURRENT_DV TARGET_REG
                    SETV %REG_TEMP 1|0
                    IFR CURRENT_DV %REG_TEMP
                    SETV TARGET_REG 0|1
                    
                    SETV %REG_TEMP -1|0
                    IFR CURRENT_DV %REG_TEMP
                    SETV TARGET_REG 0|-1
                    
                    SETV %REG_TEMP 0|1
                    IFR CURRENT_DV %REG_TEMP
                    SETV TARGET_REG -1|0
                    
                    SETV %REG_TEMP 0|-1
                    IFR CURRENT_DV %REG_TEMP
                    SETV TARGET_REG 1|0
                .ENDM
                """;
    }
}