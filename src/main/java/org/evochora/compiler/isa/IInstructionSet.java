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
	 * Resolves a register token (e.g., "%DR0") to its ID.
	 * @param token The register token to resolve.
	 * @return An optional containing the register ID, or empty if not found.
	 */
	Optional<Integer> resolveRegisterToken(String token);

	/**
	 * Names the conditional that skips the next instruction exactly when the given one does
	 * not. The two take the same operands, so the negation can stand in for the original
	 * wherever a condition has to be inverted.
	 *
	 * @param opcode The name of an instruction, in any letter case.
	 * @return The name of the negated conditional, or empty if the instruction is no conditional.
	 */
	Optional<String> negatedConditional(String opcode);

	/**
	 * The kind of an instruction argument.
	 */
	enum ArgKind {
		/** A data register operand (DR, PDR, FDR, SDR). */
		REGISTER,
		/** A location register operand (LR, PLR, FLR, SLR). */
		LOCATION_REGISTER,
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
		 * Describes the operands the instruction expects, in the order they follow the opcode.
		 *
		 * @return The list of argument types for the instruction.
		 */
		List<ArgKind> argumentTypes();

		/**
		 * Reports how many operands the instruction expects, counted from {@link #argumentTypes()}.
		 *
		 * @return The number of arguments for the instruction.
		 */
		default int getArity() { return argumentTypes().size(); }
	}
}



