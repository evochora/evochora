# Energy Seeker Organism
# This organism moves around looking for energy and consumes it when found

.ORG 0|0
START:
  # Initialize registers
  SETI %DR0 DATA:0    # Counter for moves
  SETI %DR1 DATA:0    # Energy found counter
  
  # Set initial direction (right)
  SETI %DV DATA:1|0
  
  # Main loop
MAIN_LOOP:
  # Check current energy level
  NRG %DR2
  
  # If energy is low, try to find more
  LTI %DR2 DATA:50
  JMPI SEARCH_ENERGY
  
  # Otherwise, just move around
  JMPI MOVE_AROUND

SEARCH_ENERGY:
  # Look for energy in all 4 directions
  # Check right
  SCANI %DR3 DATA:1|0
  IFTR %DR3 ENERGY:0
  JMPI FOUND_ENERGY
  
  # Check left  
  SCANI %DR3 DATA:-1|0
  IFTR %DR3 ENERGY:0
  JMPI FOUND_ENERGY
  
  # Check up
  SCANI %DR3 DATA:0|1
  IFTR %DR3 ENERGY:0
  JMPI FOUND_ENERGY
  
  # Check down
  SCANI %DR3 DATA:0|-1
  IFTR %DR3 ENERGY:0
  JMPI FOUND_ENERGY
  
  # No energy found, move randomly
  JMPI MOVE_RANDOM

FOUND_ENERGY:
  # Move to energy and consume it
  PEEKI %DR3 DATA:1|0  # Try right first
  IFTR %DR3 ENERGY:0
  JMPI CONSUME_ENERGY
  
  PEEKI %DR3 DATA:-1|0  # Try left
  IFTR %DR3 ENERGY:0
  JMPI CONSUME_ENERGY
  
  PEEKI %DR3 DATA:0|1   # Try up
  IFTR %DR3 ENERGY:0
  JMPI CONSUME_ENERGY
  
  PEEKI %DR3 DATA:0|-1  # Try down
  IFTR %DR3 ENERGY:0
  JMPI CONSUME_ENERGY
  
  # If we get here, something went wrong
  JMPI MOVE_AROUND

CONSUME_ENERGY:
  # Increment energy found counter
  ADDI %DR1 DATA:1
  
  # Move to the energy location
  SEEK DATA:1|0
  IFTR %DR3 ENERGY:0
  JMPI MOVE_AROUND
  
  SEEK DATA:-1|0
  IFTR %DR3 ENERGY:0
  JMPI MOVE_AROUND
  
  SEEK DATA:0|1
  IFTR %DR3 ENERGY:0
  JMPI MOVE_AROUND
  
  SEEK DATA:0|-1
  IFTR %DR3 ENERGY:0
  JMPI MOVE_AROUND
  
  # Continue searching
  JMPI MAIN_LOOP

MOVE_AROUND:
  # Simple movement pattern
  # Move forward
  SEEK DATA:1|0
  
  # Increment move counter
  ADDI %DR0 DATA:1
  
  # If we've moved 10 times, change direction
  LTI %DR0 DATA:10
  JMPI MAIN_LOOP
  
  # Reset counter and change direction
  SETI %DR0 DATA:0
  
  # Turn 90 degrees clockwise
  TURN DATA:0|1
  
  JMPI MAIN_LOOP

MOVE_RANDOM:
  # Generate random direction
  SETI %DR3 DATA:4
  RAND %DR3
  
  # Choose direction based on random value
  IFI %DR3 DATA:0
  TURN DATA:1|0    # Right
  
  IFI %DR3 DATA:1
  TURN DATA:-1|0   # Left
  
  IFI %DR3 DATA:2
  TURN DATA:0|1    # Up
  
  IFI %DR3 DATA:3
  TURN DATA:0|-1   # Down
  
  # Move in chosen direction
  SEEK DATA:1|0
  
  # Increment move counter
  ADDI %DR0 DATA:1
  
  # Return to main loop
  JMPI MAIN_LOOP
