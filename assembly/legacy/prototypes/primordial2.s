# Energy Decision Organism
# This organism makes decisions based on energy level in a main loop

# Define energy threshold for reproduction
.DEFINE REPRODUCTION_THRESHOLD DATA:10000

# Register aliases for better readability
.REG %CURRENT_ENERGY %DR0
.REG %SPIRAL_DIRECTION %DR2
.REG %SPIRAL_STEPS %DR3
.REG %SPIRAL_SEGMENT_LEN %DR4
.REG %SPIRAL_TURN_COUNT %DR5



# Start at origin
.ORG 0|0

# Initialize registers
START:
  # Initialize spiral walk state in registers that will be passed as parameters
  SETV %SPIRAL_DIRECTION 1|0                    # SPIRAL_DIRECTION: Start direction: right
  SETI %SPIRAL_STEPS DATA:0                     # SPIRAL_STEPS: Steps taken in current segment
  SETI %SPIRAL_SEGMENT_LEN DATA:1               # SPIRAL_SEGMENT_LEN: Current segment length
  SETI %SPIRAL_TURN_COUNT DATA:0                # SPIRAL_TURN_COUNT: Turns made in current segment
  
  # Jump to main loop in separate line
  JMPI MAIN_LOOP

# Main loop - runs continuously (placed in line 1)
.ORG 0|1
MAIN_LOOP:
  # Check current energy level
  NRG %CURRENT_ENERGY
  
  # Compare current energy with threshold
  # If energy >= threshold, jump to reproduction
  GTI %CURRENT_ENERGY REPRODUCTION_THRESHOLD
  JMPI START_REPRODUCTION
  
  # If energy < threshold, jump to energy search
  JMPI SEARCH_ENERGY

# Mock reproduction - just a NOP for now (placed in line 2)
.ORG 0|2
START_REPRODUCTION:
  NOP  # Mock reproduction logic
  JMPI MAIN_LOOP  # Return to main loop

# Mock energy search - just a NOP for now (placed in line 3)
.ORG 0|3
SEARCH_ENERGY:
  CALL SEARCH_ENERGY_PROC WITH %SPIRAL_DIRECTION %SPIRAL_STEPS %SPIRAL_SEGMENT_LEN %SPIRAL_TURN_COUNT  # Pass spiral state as parameters
  JMPI MAIN_LOOP  # Return to main loop

# Energy search procedure with spiral walk (distributed across multiple lines)
.ORG 0|4
.PROC SEARCH_ENERGY_PROC WITH SPIRAL_DIRECTION SPIRAL_STEPS SPIRAL_SEGMENT_LEN SPIRAL_TURN_COUNT
  # Procedure register aliases for better readability
  .PREG %TEMP_RESULT %PR0
  .PREG %TEMP_DIRECTION %PR1
  
  # --- Phase 1: Scan for energy in all directions ---
  SCAN_FOR_ENERGY:
  SNTI %TEMP_RESULT ENERGY:0  # Scan for energy molecules
  
  # If NO energy found, continue with spiral movement
  IFI %TEMP_RESULT DATA:0
    JMPI SPIRAL_MOVEMENT
  
  # Energy found - consume it and return
  RBIR %TEMP_DIRECTION %TEMP_RESULT     # Randomly select one energy direction
  B2VR %TEMP_DIRECTION %TEMP_DIRECTION  # Convert bitmask to vector
  PEEK %TEMP_RESULT %TEMP_DIRECTION     # Consume the energy
  RET                                   # Return to main loop (energy found!)

# Phase 2: Spiral movement pattern (placed in line 5)
.ORG 0|5
  SPIRAL_MOVEMENT:
    # Check if we've completed current segment
    IFR SPIRAL_STEPS SPIRAL_SEGMENT_LEN
      JMPI TURN_AND_RESET
    
    # Check if forward direction is passable and move if possible
    SPNR %TEMP_RESULT           # Get passable directions
    V2BR %TEMP_DIRECTION SPIRAL_DIRECTION  # Convert current direction to bitmask
    ANDR %TEMP_DIRECTION %TEMP_RESULT      # Check if forward is passable
    IFI %TEMP_DIRECTION DATA:0
      JMPI TURN_ONLY
    
    # Move forward and increment step counter
    SEEK SPIRAL_DIRECTION
    ADDI SPIRAL_STEPS DATA:1
    
    # Continue with current segment, don't scan for energy yet
    JMPI SPIRAL_MOVEMENT

# Phase 3: Turn and reset logic (placed in line 6)
.ORG 0|6
  TURN_AND_RESET:
    # 90° CW drehen, Schrittzähler zurück
    SETI %TEMP_RESULT DATA:0     # Axis X
    SETI %TEMP_DIRECTION DATA:1  # Axis Y
    RTRR SPIRAL_DIRECTION %TEMP_RESULT %TEMP_DIRECTION   # rotate 90° CW
    SETI SPIRAL_STEPS DATA:0
    ADDI SPIRAL_TURN_COUNT DATA:1

    # Nur wenn 2 Turns voll sind, Segment-Länge erhöhen und Zähler resetten
    IFI SPIRAL_TURN_COUNT DATA:2
      JMPI DO_INCREASE

    # Sonst Zähler behalten
    JMPI SCAN_FOR_ENERGY

  TURN_ONLY:
    # Nur drehen, NICHT Steps/Turn-Count zurücksetzen!
    SETI %TEMP_RESULT    DATA:0   # X
    SETI %TEMP_DIRECTION DATA:1   # Y
    RTRR SPIRAL_DIRECTION %TEMP_RESULT %TEMP_DIRECTION
    JMPI SPIRAL_MOVEMENT

  DO_INCREASE:
    # Klassische Spirale: 1,1,2,2,3,3,...
    ADDI SPIRAL_SEGMENT_LEN DATA:2   # (falls du 1,1,3,3,... willst: DATA:2)
    SETI SPIRAL_TURN_COUNT DATA:0
    JMPI SCAN_FOR_ENERGY
.ENDP
