# Evochora Assembly Examples
# This file demonstrates comprehensive assembly features including procedures, loops, world interaction,
# memory organization, and various parameter passing modes

# =============================================================================
# REGISTER ALIASES
# =============================================================================
.REG %POS_X %DR0
.REG %POS_Y %DR1
.REG %ENERGY %DR2
.REG %TEMP %DR3
.REG %COUNTER %DR4
.REG %RESULT %DR5
.REG %STACK_PTR %DR6
.REG %RETURN_VAL %DR7

# =============================================================================
# CONSTANTS
# =============================================================================
.DEFINE MAX_ENERGY DATA:100
.DEFINE MOVE_COST DATA:1
.DEFINE ENERGY_GAIN DATA:5
.DEFINE WORLD_SIZE DATA:1000
.DEFINE STACK_SIZE DATA:50
.DEFINE TRUE DATA:1
.DEFINE FALSE DATA:0

# =============================================================================
# MEMORY ORGANIZATION and .PLACE directives
# =============================================================================
# Global variables with state data (using .PLACE for absolute positioning)
GLOBAL_COUNTER:
  .PLACE DATA:123 10|10
GLOBAL_ENERGY:
  .PLACE DATA:50 11|10
MESSAGE_BUFFER:
  .PLACE DATA:72 12|10

# =============================================================================
# PROCEDURES
# =============================================================================

# Procedure with REF parameters (modifies original values)
.PROC ADD_ENERGY EXPORT REF ENERGY AMOUNT
  ADDR ENERGY AMOUNT
  RET
.ENDP

# Procedure with VAL parameters (value-only, doesn't modify originals)
.PROC CALCULATE_DISTANCE EXPORT VAL X1 Y1 X2 Y2 RESULT
  # Calculate distance between two points (simplified)
  SUBR X2 X1
  MULR RESULT RESULT
  SUBR Y2 Y1
  MULR RESULT RESULT
  # Square root would be more complex, so we'll just return the sum
  ADDR RESULT X1
  ADDR RESULT Y1
  RET
.ENDP

# Procedure with mixed REF and VAL parameters
.PROC UPDATE_POSITION EXPORT REF X Y VAL DELTA_X DELTA_Y
  ADDR X DELTA_X
  ADDR Y DELTA_Y
  RET
.ENDP

# Procedure that returns a value
.PROC GET_ENERGY_LEVEL EXPORT REF ENERGY RETURN_VAL
  SETR RETURN_VAL ENERGY
  RET
.ENDP

# Procedure to consume energy for movement
.PROC CONSUME_ENERGY EXPORT REF ENERGY
  SUBI ENERGY DATA:1
  # Ensure energy doesn't go below 0
  LTI ENERGY DATA:0
  JMPI ZERO_ENERGY
  RET

ZERO_ENERGY:
  SETI ENERGY DATA:0
  RET
.ENDP

# Procedure to move north
.PROC MOVE_NORTH EXPORT REF X Y
  SUBI Y DATA:1
  RET
.ENDP

# Procedure to move east
.PROC MOVE_EAST EXPORT REF X Y
  ADDI X DATA:1
  RET
.ENDP

# Procedure to move south
.PROC MOVE_SOUTH EXPORT REF X Y
  ADDI Y DATA:1
  RET
.ENDP

# Procedure to move west
.PROC MOVE_WEST EXPORT REF X Y
  SUBI X DATA:1
  RET
.ENDP

# Procedure to check if position is valid (simplified)
.PROC IS_VALID_POS EXPORT REF X Y RESULT
  # Check X >= 0
  LTI X DATA:0
  JMPI INVALID_POS
  
  # Check Y >= 0
  LTI Y DATA:0
  JMPI INVALID_POS
  
  # Valid position
  SETI RESULT DATA:1
  RET

INVALID_POS:
  SETI RESULT DATA:0
  RET
.ENDP

# Procedure with local variables and complex logic
.PROC FIND_NEAREST_ENERGY EXPORT REF X Y ENERGY FOUND
  # Local variables (using registers)
  SETI %TEMP DATA:0
  SETI %COUNTER DATA:0
  
  # Search in a 3x3 grid around current position
SEARCH_LOOP:
  # Check if we found energy (simplified)
  LTI %COUNTER DATA:9
  JMPI SEARCH_COMPLETE
  
  # Simulate energy search
  LTI %COUNTER DATA:3
  JMPI FOUND_ENERGY_SOURCE
  
  # Continue searching
  ADDI %COUNTER DATA:1
  JMPI SEARCH_LOOP

FOUND_ENERGY_SOURCE:
  SETI FOUND TRUE
  RET

SEARCH_COMPLETE:
  SETI FOUND FALSE
  RET
.ENDP

# Procedure that demonstrates stack operations
.PROC PUSH_VALUE EXPORT REF STACK_PTR VAL VALUE
  # Push value onto stack
  SETI %TEMP DATA:0
  ADDI %TEMP DATA:1
  SETR STACK_PTR %TEMP
  # Store value at stack position (simplified)
  RET
.ENDP

# Procedure that initializes system registers
.PROC SYSTEM_INIT EXPORT REF SYSTEM_FLAGS MEMORY_PTR
  # Initialize system registers with default values
  SETI SYSTEM_FLAGS DATA:0    # System flags
  SETI MEMORY_PTR DATA:1000   # Memory pointer
  
  # Set up call stack (using local variable)
  SETI %TEMP DATA:2000
  # Set interrupt handler address (using local variable)
  SETI %TEMP DATA:3000
  
  RET
.ENDP

# Procedure that uses registers for memory operations
.PROC MEMORY_READ EXPORT REF MEMORY_PTR VAL ADDRESS RESULT
  # Read from memory using register
  SETR MEMORY_PTR ADDRESS
  # Simulate memory read (simplified)
  SETI RESULT DATA:42
  RET
.ENDP

# Procedure that demonstrates system flags
.PROC SET_FLAG EXPORT REF SYSTEM_FLAGS VAL FLAG_BIT
  # Set a specific flag bit
  SETR SYSTEM_FLAGS FLAG_BIT
  RET
.ENDP

# Procedure that demonstrates reading state data
.PROC READ_STATE_DATA EXPORT REF COUNTER ENERGY
  # This procedure simulates reading state from .PLACE data
  # In a real implementation, this would read from the absolute memory locations
  SETI COUNTER DATA:123  # Simulates reading from GLOBAL_COUNTER's .PLACE
  SETI ENERGY DATA:50    # Simulates reading from GLOBAL_ENERGY's .PLACE
  RET
.ENDP


# Procedure that demonstrates conditional execution
.PROC CONDITIONAL_MOVE EXPORT REF X Y VAL CONDITION
  # Only move if condition is true
  LTI CONDITION DATA:0
  JMPI NO_MOVE
  
  # Move north
  SUBI Y DATA:1
  RET

NO_MOVE:
  RET
.ENDP

# =============================================================================
# MAIN PROGRAM
# =============================================================================

START:
  # Initialize position
  SETI %POS_X DATA:500
  SETI %POS_Y DATA:500
  
  # Initialize energy
  SETI %ENERGY DATA:50
  
  # Initialize counter
  SETI %COUNTER DATA:0
  
  # Initialize stack pointer
  SETI %STACK_PTR DATA:0
  
  # Initialize global variables (using registers instead of labels)
  SETI %TEMP DATA:0
  SETI %TEMP DATA:50
  
  # Initialize system using registers
  CALL SYSTEM_INIT REF %TEMP %TEMP
  
  # Main behavior loop
BEHAVIOR_LOOP:
  # Add some energy
  CALL ADD_ENERGY REF %ENERGY ENERGY_GAIN
  
  # Cap energy at maximum
  LTI %ENERGY MAX_ENERGY
  JMPI ENERGY_CAPPED
  SETI %ENERGY MAX_ENERGY

ENERGY_CAPPED:
  # Try to move if we have enough energy
  LTI %ENERGY MOVE_COST
  JMPI NOT_ENOUGH_ENERGY
  
  # Move north
  CALL MOVE_NORTH REF %POS_X %POS_Y
  
  # Check if new position is valid
  CALL IS_VALID_POS REF %POS_X %POS_Y %TEMP
  LTI %TEMP DATA:1
  JMPI VALID_MOVE
  
  # Invalid move, try east
  CALL MOVE_EAST REF %POS_X %POS_Y
  CALL IS_VALID_POS REF %POS_X %POS_Y %TEMP
  LTI %TEMP DATA:1
  JMPI VALID_MOVE
  
  # Invalid move, try south
  CALL MOVE_SOUTH REF %POS_X %POS_Y
  CALL IS_VALID_POS REF %POS_X %POS_Y %TEMP
  LTI %TEMP DATA:1
  JMPI VALID_MOVE
  
  # Invalid move, try west
  CALL MOVE_WEST REF %POS_X %POS_Y
  CALL IS_VALID_POS REF %POS_X %POS_Y %TEMP
  LTI %TEMP DATA:1
  JMPI VALID_MOVE
  
  # All moves invalid, just consume energy
  JMPI CONSUME_AND_CONTINUE

VALID_MOVE:
  # Valid move, consume energy
  CALL CONSUME_ENERGY REF %ENERGY
  JMPI BEHAVIOR_LOOP

CONSUME_AND_CONTINUE:
  # Consume energy even if no valid move
  CALL CONSUME_ENERGY REF %ENERGY
  JMPI BEHAVIOR_LOOP

NOT_ENOUGH_ENERGY:
  # Wait and try again (simplified)
  JMPI BEHAVIOR_LOOP

# =============================================================================
# ADVANCED FEATURES DEMONSTRATION
# =============================================================================

# Demonstrate various procedure calls
DEMO_PROCEDURES:
  # Call procedure with VAL parameters
  CALL CALCULATE_DISTANCE VAL %POS_X %POS_Y DATA:100 DATA:200 %RESULT
  
  # Call procedure with mixed REF and VAL parameters
  CALL UPDATE_POSITION REF %POS_X %POS_Y VAL DATA:5 DATA:10
  
  # Call procedure that returns a value
  CALL GET_ENERGY_LEVEL REF %ENERGY %RETURN_VAL
  
  # Call procedure with conditional execution
  CALL CONDITIONAL_MOVE REF %POS_X %POS_Y VAL TRUE
  
  # Call procedure with complex logic
  CALL FIND_NEAREST_ENERGY REF %POS_X %POS_Y %ENERGY %TEMP
  
  # Call procedure with stack operations
  CALL PUSH_VALUE REF %STACK_PTR VAL %ENERGY
  
  # Call procedure with registers
  CALL MEMORY_READ REF %TEMP VAL DATA:50 %TEMP
  
  # Call procedure with system flags
  CALL SET_FLAG REF %TEMP VAL DATA:1
  
  # Call procedure that reads state data (simulates reading from .PLACE data)
  CALL READ_STATE_DATA REF %COUNTER %ENERGY
  
  JMPI PLACE_ORGANISM

# Place organism in world at current position
PLACE_ORGANISM:
  POKI %POS_X 1|0
  POKI %POS_Y 2|0
  POKI %ENERGY 3|0
  POKI %RESULT 4|0
  POKI %RETURN_VAL 5|0
  
  # End of program (in real implementation, this would loop)
  JMPI START