package org.evochora.runtime.isa;

import java.util.Collections;
import java.util.List;

/**
 * Describes the expected signature of an instruction, i.e., the number
 * and types of its arguments.
 *
 * @param argumentTypes An unmodifiable list of the expected argument types.
 */
public record InstructionSignature(List<InstructionArgumentType> argumentTypes) {

    /**
     * Creates a signature and ensures that the list is unmodifiable.
     * @param argumentTypes The list of argument types.
     */
    public InstructionSignature(List<InstructionArgumentType> argumentTypes) {
        this.argumentTypes = Collections.unmodifiableList(argumentTypes);
    }

    /**
     * Returns the expected number of arguments.
     * @return The number of arguments.
     */
    public int getArity() {
        return argumentTypes.size();
    }

    /**
     * Static helper method for instructions with no arguments.
     * @return An empty signature.
     */
    public static InstructionSignature noArgs() {
        return new InstructionSignature(List.of());
    }

    /**
     * Static helper method for instructions with one argument.
     * @param type1 The type of the first argument.
     * @return A signature with one argument.
     */
    public static InstructionSignature of(InstructionArgumentType type1) {
        return new InstructionSignature(List.of(type1));
    }

    /**
     * Static helper method for instructions with two arguments.
     * @param type1 The type of the first argument.
     * @param type2 The type of the second argument.
     * @return A signature with two arguments.
     */
    public static InstructionSignature of(InstructionArgumentType type1, InstructionArgumentType type2) {
        return new InstructionSignature(List.of(type1, type2));
    }

    /**
     * Static helper method for instructions with three arguments.
     * @param type1 The type of the first argument.
     * @param type2 The type of the second argument.
     * @param type3 The type of the third argument.
     * @return A signature with three arguments.
     */
    public static InstructionSignature of(InstructionArgumentType type1, InstructionArgumentType type2, InstructionArgumentType type3) {
        return new InstructionSignature(List.of(type1, type2, type3));
    }
}