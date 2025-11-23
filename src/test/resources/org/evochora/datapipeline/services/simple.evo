# Evochora Assembly Example
# This file demonstrates basic assembly syntax and can be used to test the compile system

# Register aliases
.REG %COUNTER %DR0
.REG %RESULT %DR1
.REG %TEMP %DR2

# Define constants
.DEFINE MAX_COUNT DATA:10
.DEFINE INIT_VALUE DATA:1

# Simple procedure that adds two values
.PROC ADD_TWO EXPORT REF A B
  ADDR A B
  RET
.ENDP

# Main program
START:
  # Initialize counter
  SETI %COUNTER INIT_VALUE
  
  # Simple loop
LOOP:
  # Add counter to result
  SETR %TEMP %COUNTER
  CALL ADD_TWO REF %RESULT %TEMP
  
  # Increment counter
  ADDI %COUNTER DATA:1
  
  # Check if we should continue
  LTI %COUNTER MAX_COUNT
  JMPI LOOP
  
  # Place result in world
  POKI %RESULT 1|0
  
  # End program
  JMPI START
