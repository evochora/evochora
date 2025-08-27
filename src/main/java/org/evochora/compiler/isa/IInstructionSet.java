package org.evochora.compiler.isa;

import java.util.List;
import java.util.Optional;

/**
 * Stable ISA interface used by the compiler backend to avoid direct coupling
 * to runtime ISA implementation details.
 */
public interface IInstructionSet {

	/**
	 * Gets the ID of an instruction by its name.
	 * @param name The name of the instruction.
	 * @return An optional containing the instruction ID, or empty if not found.
	 */
	Optional<Integer> getInstructionIdByName(String name);

	/**
	 * Gets the signature of an instruction by its ID.
	 * @param id The ID of the instruction.
	 * @return An optional containing the instruction signature, or empty if not found.
	 */
	Optional<Signature> getSignatureById(int id);

	/**
	 * Gets the length of an instruction by its ID.
	 * @param id The ID of the instruction.
	 * @return The length of the instruction in memory slots.
	 */
	int getInstructionLengthById(int id);

	/**
	 * Resolves a register token (e.g., "%DR0") to its ID.
	 * @param token The register token to resolve.
	 * @return An optional containing the register ID, or empty if not found.
	 */
	Optional<Integer> resolveRegisterToken(String token);

	/**
	 * The kind of an instruction argument.
	 */
	enum ArgKind {
		/** A register operand. */
		REGISTER,
		/** A literal value operand. */
		LITERAL,
		/** A vector operand. */
		VECTOR,
		/** A label operand. */
		LABEL
	}

	/**
	 * Represents the signature of an instruction.
	 */
	interface Signature {
		/**
		 * @return The list of argument types for the instruction.
		 */
		List<ArgKind> argumentTypes();

		/**
		 * @return The number of arguments for the instruction.
		 */
		default int getArity() { return argumentTypes().size(); }
	}
}



