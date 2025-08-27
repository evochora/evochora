package org.evochora.runtime.services;

import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.InstructionSignature;
import org.evochora.runtime.isa.InstructionArgumentType;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.IEnvironmentReader;

import java.util.Optional;

/**
 * Central disassembler that works with any IEnvironmentReader implementation.
 * This eliminates the need to duplicate disassembly logic between Runtime
 * and Debugger components.
 */
public class Disassembler {

    /**
     * Disassembles an instruction at the given coordinates.
     * Returns simple data structure without objects for maximum performance.
     *
     * @param reader The environment reader to use.
     * @param instructionPointer The coordinates of the instruction.
     * @return The disassembled instruction data, or null if disassembly fails.
     */
    public DisassemblyData disassemble(IEnvironmentReader reader, int[] instructionPointer) {
        try {
            // Read the opcode
            Molecule opcodeMolecule = reader.getMolecule(instructionPointer);
            if (opcodeMolecule == null) {
                return null;
            }

            int opcodeId = opcodeMolecule.toInt();
            String opcodeName = Instruction.getInstructionNameById(opcodeId);

            // Handle unknown opcodes
            if ("UNKNOWN".equals(opcodeName)) {
                return new DisassemblyData(opcodeId, "UNKNOWN_OP (" + opcodeId + ")", new int[0], new int[0][]);
            }

            // Get the instruction signature
            Optional<InstructionSignature> signatureOpt = Instruction.getSignatureById(opcodeId);
            if (signatureOpt.isEmpty()) {
                return new DisassemblyData(opcodeId, opcodeName, new int[0], new int[0][]);
            }

            InstructionSignature signature = signatureOpt.get();

            // Read the arguments
            int totalArgs = calculateTotalArguments(signature, reader.getShape().length);
            int[] argValues = new int[totalArgs];
            int[][] argPositions = new int[totalArgs][];
            int[] currentPos = instructionPointer.clone();
            int actualArgCount = 0;

            for (int i = 0; i < signature.argumentTypes().size(); i++) {
                InstructionArgumentType argType = signature.argumentTypes().get(i);
                
                if (argType == InstructionArgumentType.VECTOR) {
                    // VECTOR: Read n-dimensions
                    int dims = reader.getShape().length;
                    for (int dim = 0; dim < dims; dim++) {
                        // Move to the next position
                        currentPos = reader.getProperties().getNextPosition(currentPos, new int[]{1, 0});
                        
                        // Read the argument
                        Molecule argMolecule = reader.getMolecule(currentPos);
                        if (argMolecule == null) {
                            // Handle incomplete instruction
                            break;
                        }
                        
                        argValues[actualArgCount] = argMolecule.toInt();
                        argPositions[actualArgCount] = currentPos.clone();
                        actualArgCount++;
                    }
                } else if (argType == InstructionArgumentType.LABEL) {
                    // LABEL: Read n-dimensions (because a label is a vector alias)
                    int dims = reader.getShape().length;
                    for (int dim = 0; dim < dims; dim++) {
                        // Move to the next position
                        currentPos = reader.getProperties().getNextPosition(currentPos, new int[]{1, 0});
                        
                        // Read the argument
                        Molecule argMolecule = reader.getMolecule(currentPos);
                        if (argMolecule == null) {
                            // Handle incomplete instruction
                            break;
                        }
                        
                        argValues[actualArgCount] = argMolecule.toInt();
                        argPositions[actualArgCount] = currentPos.clone();
                        actualArgCount++;
                    }
                } else {
                    // REGISTER/LITERAL: Read one argument
                    // Move to the next position
                    currentPos = reader.getProperties().getNextPosition(currentPos, new int[]{1, 0});
                    
                    // Read the argument
                    Molecule argMolecule = reader.getMolecule(currentPos);
                    if (argMolecule == null) {
                        // Handle incomplete instruction
                        break;
                    }
                    
                    argValues[actualArgCount] = argMolecule.toInt();
                    argPositions[actualArgCount] = currentPos.clone();
                    actualArgCount++;
                }
            }

            // Create properly sized arrays with only the arguments we actually found
            int[] finalArgValues = new int[actualArgCount];
            int[][] finalArgPositions = new int[actualArgCount][];
            System.arraycopy(argValues, 0, finalArgValues, 0, actualArgCount);
            System.arraycopy(argPositions, 0, finalArgPositions, 0, actualArgCount);

            return new DisassemblyData(opcodeId, opcodeName, finalArgValues, finalArgPositions);

        } catch (Exception e) {
            // Log error and return null
            return null;
        }
    }

    /**
     * Calculates the total number of arguments based on the signature.
     * VECTOR/LABEL count as n-dimensions, REGISTER/LITERAL as 1.
     * @param signature The instruction signature.
     * @param worldDimensions The number of dimensions in the world.
     * @return The total number of arguments.
     */
    private int calculateTotalArguments(InstructionSignature signature, int worldDimensions) {
        int total = 0;
        for (InstructionArgumentType argType : signature.argumentTypes()) {
            if (argType == InstructionArgumentType.VECTOR || argType == InstructionArgumentType.LABEL) {
                total += worldDimensions;
            } else {
                total += 1;
            }
        }
        return total;
    }

}
